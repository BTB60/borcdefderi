package az.borcdefteri.notifications;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.reports.DaySummaryService;
import az.borcdefteri.reports.DaySummaryService.DaySummary;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Hər axşam sahibə Telegram hesabatı. The owner opens the bot with a one-time code (t.me/bot?start=CODE), the bot
 * remembers that chat, and every evening at the chosen hour the day summary of the store goes there.
 * One platform bot (TELEGRAM_BOT_TOKEN); updates are read by polling, so no webhook has to be set up.
 */
@Service
@RestController
public class TelegramService {

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    public record Status(boolean configured, String botUsername, boolean linked, int hour, LocalDate lastSent) {}

    public record Link(String url, String code) {}

    public record Settings(Integer hour) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final DaySummaryService days;
    private final ObjectMapper json;
    private final String token;
    private final String username;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private long offset = 0;

    public TelegramService(JdbcClient jdbc, TenantService tenant, DaySummaryService days, ObjectMapper json,
                           @Value("${app.telegram.bot-token:}") String token, @Value("${app.telegram.bot-username:}") String username) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.days = days;
        this.json = json;
        this.token = token == null ? "" : token.trim();
        this.username = username == null ? "" : username.trim().replaceFirst("^@", "");
    }

    boolean configured() {
        return !token.isEmpty() && !username.isEmpty();
    }

    // ---------- owner settings ----------

    @GetMapping("/api/telegram")
    public Status status() {
        CurrentMember m = tenant.requireOwner();
        return jdbc.sql("SELECT telegram_chat_id, telegram_report_hour, telegram_last_sent FROM stores WHERE id = :s")
                .param("s", m.storeId()).query((rs, n) -> new Status(configured(), username, rs.getObject("telegram_chat_id") != null,
                        rs.getInt("telegram_report_hour"), rs.getObject("telegram_last_sent", LocalDate.class))).single();
    }

    /** A new one-time code and the link that opens the bot with it. */
    @PostMapping("/api/telegram/link")
    public Link link() {
        CurrentMember m = tenant.requireOwner();
        if (!configured()) throw ApiException.conflict("TELEGRAM_OFF", "Telegram botu hələ qoşulmayıb (administrator)");
        String code = code();
        jdbc.sql("UPDATE stores SET telegram_link_code = :c WHERE id = :s").param("c", code).param("s", m.storeId()).update();
        return new Link("https://t.me/" + username + "?start=" + code, code);
    }

    @PutMapping("/api/telegram")
    public Status settings(@RequestBody Settings req) {
        CurrentMember m = tenant.requireOwner();
        if (req.hour() == null || req.hour() < 0 || req.hour() > 23) throw ApiException.badRequest("INVALID_HOUR", "Saat 0–23 olmalıdır");
        jdbc.sql("UPDATE stores SET telegram_report_hour = :h WHERE id = :s").param("h", req.hour()).param("s", m.storeId()).update();
        return status();
    }

    @DeleteMapping("/api/telegram")
    public Status unlink() {
        CurrentMember m = tenant.requireOwner();
        jdbc.sql("UPDATE stores SET telegram_chat_id = NULL, telegram_link_code = NULL WHERE id = :s").param("s", m.storeId()).update();
        return status();
    }

    /** Sends today's report now (to try it). */
    @PostMapping("/api/telegram/test")
    public Map<String, Boolean> test() {
        CurrentMember m = tenant.requireOwner();
        Long chat = jdbc.sql("SELECT telegram_chat_id FROM stores WHERE id = :s").param("s", m.storeId()).query(Long.class).optional().orElse(null);
        if (chat == null) throw ApiException.conflict("TELEGRAM_NOT_LINKED", "Əvvəlcə Telegram-ı qoşun");
        return Map.of("sent", send(chat, report(m.storeId(), m.storeName(), tenant.today())));
    }

    private static String code() {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    // ---------- the bot ----------

    /** Reads new messages: "/start CODE" links that chat to the store with that code. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void poll() {
        if (!configured()) return;
        try {
            JsonNode res = call("getUpdates?timeout=0&allowed_updates=%5B%22message%22%5D&offset=" + offset);
            if (res == null || !res.path("ok").asBoolean()) return;
            for (JsonNode u : res.path("result")) {
                offset = Math.max(offset, u.path("update_id").asLong() + 1);
                handle(u);
            }
        } catch (Exception e) {
            log.debug("telegram poll failed: {}", e.getMessage());
        }
    }

    /** One update from Telegram (also used by the tests). */
    public void handle(JsonNode update) {
        JsonNode msg = update.path("message");
        long chat = msg.path("chat").path("id").asLong(0);
        String text = msg.path("text").asText("").trim();
        if (chat == 0 || !text.startsWith("/start")) return;
        String code = text.substring("/start".length()).trim().toUpperCase();
        if (code.isEmpty()) {
            send(chat, "Salam! Mağazanızın hesabatı üçün HesabPro-da <b>Parametrlər → Telegram hesabatı</b> bölməsindən “Qoş” düyməsinə basın.");
            return;
        }
        var store = jdbc.sql("SELECT id, name FROM stores WHERE telegram_link_code = :c")
                .param("c", code).query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name"))).optional();
        if (store.isEmpty()) {
            send(chat, "Bu kod artıq işlədilib və ya səhvdir. HesabPro-da “Qoş” düyməsinə yenidən basın.");
            return;
        }
        jdbc.sql("UPDATE stores SET telegram_chat_id = :c, telegram_link_code = NULL WHERE id = :s")
                .param("c", chat).param("s", store.get().getKey()).update();
        int hour = jdbc.sql("SELECT telegram_report_hour FROM stores WHERE id = :s").param("s", store.get().getKey()).query(Integer.class).single();
        send(chat, "✅ <b>" + esc(store.get().getValue()) + "</b> qoşuldu.\nHər gün saat <b>" + hour + ":00</b>-da günün hesabatı bura gələcək.");
    }

    /** Every 10 minutes: stores whose report hour has come and that did not get today's report yet. */
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Baku")
    public void evening() {
        if (!configured()) return;
        sendDue(ZonedDateTime.now(BAKU));
    }

    /** Sends the due reports for this moment; returns how many went (public for the tests). */
    public int sendDue(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        record Due(UUID id, String name, long chat) {}
        var due = jdbc.sql("""
                SELECT id, name, telegram_chat_id FROM stores
                WHERE telegram_chat_id IS NOT NULL AND telegram_report_hour <= :h
                  AND (telegram_last_sent IS NULL OR telegram_last_sent < :d)
                """).param("h", now.getHour()).param("d", today)
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("telegram_chat_id"))).list();
        int sent = 0;
        for (Due d : due) {
            // marked first, so a slow Telegram never sends the same report twice
            jdbc.sql("UPDATE stores SET telegram_last_sent = :d WHERE id = :s").param("d", today).param("s", d.id()).update();
            try {
                if (send(d.chat(), report(d.id(), d.name(), today))) sent++;
            } catch (Exception e) {
                log.warn("telegram report for store {} failed: {}", d.id(), e.getMessage());
            }
        }
        return sent;
    }

    // ---------- the message ----------

    /** The evening report as Telegram HTML. */
    public String report(UUID storeId, String storeName, LocalDate day) {
        DaySummary s = days.summaryFor(storeId, storeName, day);
        long low = jdbc.sql("""
                SELECT COUNT(*) FROM products WHERE store_id = :s AND active AND system_kind IS NULL AND min_stock > 0 AND stock_qty <= min_stock
                """).param("s", storeId).query(Long.class).single();
        record Gap(String name, BigDecimal diff) {}
        var gaps = jdbc.sql("""
                SELECT u.full_name, SUM(sh.difference) AS diff FROM cash_shifts sh JOIN users u ON u.id = sh.user_id
                WHERE sh.store_id = :s AND sh.closed_at IS NOT NULL AND CAST(sh.closed_at AT TIME ZONE 'Asia/Baku' AS date) = :d
                  AND sh.difference <> 0
                GROUP BY u.full_name
                """).param("s", storeId).param("d", day)
                .query((rs, n) -> new Gap(rs.getString("full_name"), rs.getBigDecimal("diff"))).list();

        StringBuilder b = new StringBuilder();
        b.append("📊 <b>").append(esc(storeName)).append("</b> — ").append(day.getDayOfMonth()).append(".")
                .append(String.format("%02d", day.getMonthValue())).append(".").append(day.getYear()).append("\n\n");
        if (s.salesCount() == 0) {
            b.append("Bu gün satış olmayıb.\n");
        } else {
            b.append("🛒 Satış: <b>").append(money(s.salesTotal())).append("</b> (").append(s.salesCount()).append(" çek)\n");
            b.append("   💵 Nağd: ").append(money(s.paidCash())).append("\n");
            b.append("   💳 Kart: ").append(money(s.paidCard())).append("\n");
            if (s.credit().signum() > 0) b.append("   📒 Nisyə: ").append(money(s.credit())).append("\n");
            b.append("📈 Mənfəət (təxmini): <b>").append(money(s.grossProfit())).append("</b>\n");
        }
        if (s.returnsCount() > 0) b.append("↩️ Qaytarma: ").append(money(s.returnsTotal())).append(" (").append(s.returnsCount()).append(")\n");
        if (s.expensesTotal().signum() > 0) b.append("💸 Xərclər: ").append(money(s.expensesTotal())).append("\n");
        if (s.debtsCollected().signum() > 0) b.append("✅ Yığılan borc: ").append(money(s.debtsCollected())).append("\n");
        if (s.supplierPaid().signum() > 0) b.append("🚚 Tədarükçüyə ödəniş: ").append(money(s.supplierPaid())).append("\n");
        if (!gaps.isEmpty()) {
            b.append("\n⚠️ <b>Kassa fərqi</b>\n");
            for (Gap g : gaps) {
                b.append("   ").append(esc(g.name())).append(": ").append(g.diff().signum() > 0 ? "artıq +" : "əskik −")
                        .append(money(g.diff().abs())).append("\n");
            }
        }
        if (!s.accounts().isEmpty()) {
            b.append("\n🏦 <b>Kassa və hesablar</b>\n");
            for (var a : s.accounts()) b.append("   ").append(esc(a.name())).append(": ").append(money(a.balance())).append("\n");
        }
        if (!s.topProducts().isEmpty()) {
            b.append("\n🏆 <b>Ən çox satılan</b>\n");
            int i = 1;
            for (var p : s.topProducts()) {
                b.append("   ").append(i++).append(". ").append(esc(String.valueOf(p.get("name")))).append(" — ")
                        .append(money((BigDecimal) p.get("total"))).append("\n");
            }
        }
        if (low > 0) b.append("\n📦 Bitmək üzrə olan mal: <b>").append(low).append("</b>\n");
        return b.toString();
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + " ₼";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- Telegram API ----------

    boolean send(long chat, String html) {
        if (!configured()) return false;
        try {
            String body = "chat_id=" + chat + "&parse_mode=HTML&disable_web_page_preview=true&text="
                    + URLEncoder.encode(html, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 403) {
                // the owner blocked the bot: stop sending there
                jdbc.sql("UPDATE stores SET telegram_chat_id = NULL WHERE telegram_chat_id = :c").param("c", chat).update();
            }
            return res.statusCode() == 200;
        } catch (Exception e) {
            log.warn("telegram send failed: {}", e.getMessage());
            return false;
        }
    }

    private JsonNode call(String method) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return json.readTree(res.body());
    }
}
