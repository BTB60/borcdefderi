package az.borcdefteri;

import az.borcdefteri.notifications.TelegramService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Owner's evening Telegram report: linking a chat with a one-time code, the due time, the message itself. */
class TelegramReportTest extends IntegrationTestBase {

    @Autowired TelegramService telegram;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void ownerLinksAChatAndGetsTheDayReport() throws Exception {
        String owner = register("Telegram Mağazası");
        activateSubscription(owner, "MONTHLY");
        UUID store = UUID.fromString(body(get(owner, "/api/auth/me")).get("storeId").asText());

        // no bot token in tests: the page says so, linking is refused
        get(owner, "/api/telegram").andExpect(status().isOk()).andExpect(jsonPath("$.configured", is(false)))
                .andExpect(jsonPath("$.linked", is(false))).andExpect(jsonPath("$.hour", is(22)));
        post(owner, "/api/telegram/link", Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("TELEGRAM_OFF")));

        // the bot gets "/start CODE" → that chat is the store's
        jdbc.sql("UPDATE stores SET telegram_link_code = 'ABCDEFGH23' WHERE id = :s").param("s", store).update();
        telegram.handle(mapper.readTree("{\"update_id\":1,\"message\":{\"chat\":{\"id\":777001},\"text\":\"/start abcdefgh23\"}}"));
        get(owner, "/api/telegram").andExpect(jsonPath("$.linked", is(true)));
        // the code works once
        assertThat(jdbc.sql("SELECT telegram_link_code FROM stores WHERE id = :s").param("s", store).query(String.class).optional()).isEmpty();
        // a stranger with a wrong code changes nothing
        telegram.handle(mapper.readTree("{\"update_id\":2,\"message\":{\"chat\":{\"id\":999},\"text\":\"/start WRONGCODE1\"}}"));
        assertThat(jdbc.sql("SELECT telegram_chat_id FROM stores WHERE id = :s").param("s", store).query(Long.class).single()).isEqualTo(777001L);

        // hour setting
        put(owner, "/api/telegram", Map.of("hour", 21)).andExpect(jsonPath("$.hour", is(21)));
        put(owner, "/api/telegram", Map.of("hour", 30)).andExpect(status().isBadRequest());

        // a sale today shows in the report
        String p = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Çay <Azərçay>", "purchasePrice", "3.00",
                "salePrice", "5.00", "initialStock", "10"))).andExpect(status().isCreated())).get("id").asText();
        Map<String, Object> sale = new HashMap<>();
        sale.put("items", List.of(Map.of("productId", p, "quantity", "2")));
        sale.put("paidCash", "10.00");
        post(owner, "/api/sales", sale).andExpect(status().isCreated());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Baku"));
        String text = telegram.report(store, "Telegram Mağazası", today);
        assertThat(text).contains("Telegram Mağazası").contains("10.00 ₼").contains("(1 çek)").contains("Mənfəət (təxmini): <b>4.00 ₼</b>")
                .contains("Çay &lt;Azərçay&gt;");

        // before the hour nothing is due; at the hour it is, once
        ZonedDateTime evening = today.atTime(21, 5).atZone(ZoneId.of("Asia/Baku"));
        telegram.sendDue(evening.minusHours(2));
        assertThat(jdbc.sql("SELECT telegram_last_sent FROM stores WHERE id = :s").param("s", store).query(LocalDate.class).optional()).isEmpty();
        telegram.sendDue(evening);
        assertThat(jdbc.sql("SELECT telegram_last_sent FROM stores WHERE id = :s").param("s", store).query(LocalDate.class).single()).isEqualTo(today);

        // unlink
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/telegram")
                .header("Authorization", "Bearer " + owner)).andExpect(jsonPath("$.linked", is(false)));
    }
}
