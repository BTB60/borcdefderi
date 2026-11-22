package az.borcdefteri.assistant;

import az.borcdefteri.assistant.AssistantGateway.ToolResult;
import az.borcdefteri.assistant.AssistantGateway.ToolSpec;
import az.borcdefteri.cash.CashService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.customers.PartyProfileService;
import az.borcdefteri.customers.PartyService;
import az.borcdefteri.dashboard.DashboardService;
import az.borcdefteri.debts.DebtService;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.inventory.InventoryService;
import az.borcdefteri.sales.ProfitService;
import az.borcdefteri.sales.SalesService;
import az.borcdefteri.tenant.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The assistant's tools. READ-ONLY on purpose: every tool calls an existing service in the signed-in user's own
 * request, so the store, the permissions and the package modules are checked exactly as on the screens. A tool the
 * user may not use returns that as an error and the model explains it. Long lists are cut to keep answers cheap.
 */
@Component
public class AssistantTools {

    static final int MAX_ITEMS = 25;
    static final int MAX_CHARS = 12_000;

    private final DashboardService dashboard;
    private final PartyService parties;
    private final PartyProfileService profiles;
    private final DebtService debts;
    private final ProfitService profit;
    private final InventoryService inventory;
    private final SalesService sales;
    private final CashService cash;
    private final TenantService tenant;
    private final ObjectMapper json;

    public AssistantTools(DashboardService dashboard, PartyService parties, PartyProfileService profiles, DebtService debts,
                          ProfitService profit, InventoryService inventory, SalesService sales, CashService cash,
                          TenantService tenant, ObjectMapper json) {
        this.dashboard = dashboard;
        this.parties = parties;
        this.profiles = profiles;
        this.debts = debts;
        this.profit = profit;
        this.inventory = inventory;
        this.sales = sales;
        this.cash = cash;
        this.tenant = tenant;
        this.json = json;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> en(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

    private static final String DATE = "Tarix, YYYY-MM-DD. Verilməsə: bu ayın əvvəli / bu gün";

    public List<ToolSpec> specs() {
        return List.of(
                new ToolSpec("store_summary",
                        "Mağazanın ümumi vəziyyəti: müştəri borcları, şirkətlərin bizə və bizim şirkətlərə borcu, bu gün və bu ay alınan ödənişlər.",
                        Map.of(), List.of()),
                new ToolSpec("find_customers",
                        "Müştəriləri ada, telefona və ya koda görə axtarır; hər birinin açıq borcu (openBalance), gecikmiş borc sayı və bonusu ilə. Boş axtarış bütün müştəriləri qaytarır (ilk 25).",
                        Map.of("query", str("Ad, telefon və ya müştəri kodu (məs. HP-000012). Boş ola bilər.")), List.of()),
                new ToolSpec("find_companies",
                        "Şirkətləri (tədarükçüləri) ada və ya telefona görə axtarır, açıq borc balansı ilə.",
                        Map.of("query", str("Şirkətin adı və ya telefonu. Boş ola bilər.")), List.of()),
                new ToolSpec("customer_profile",
                        "Bir müştərinin tam kartı: cəmi borc, ödənilən, qalıq, borcları və son ödənişləri. id find_customers-dən gəlir.",
                        Map.of("customer_id", str("Müştərinin id-si (UUID)")), List.of("customer_id")),
                new ToolSpec("list_debts",
                        "Borclar siyahısı. type: CUSTOMER_DEBT (müştərilərin bizə borcu), COMPANY_RECEIVABLE (şirkətin bizə borcu), COMPANY_PAYABLE (bizim şirkətə borcumuz).",
                        Map.of("type", en("Borcun növü", "CUSTOMER_DEBT", "COMPANY_RECEIVABLE", "COMPANY_PAYABLE"),
                                "status", en("UNPAID: ödənilməmiş və qismən; PAID: bağlanmış; OPEN; PARTIAL", "UNPAID", "PAID", "OPEN", "PARTIAL"),
                                "query", str("Ad, telefon və ya açıqlamada axtarış. Boş ola bilər.")),
                        List.of("type")),
                new ToolSpec("sales_report",
                        "Dövr üzrə gəlir və mənfəət: xalis satış, qaytarmalar, maya dəyəri, ümumi mənfəət, xərclər, xalis nəticə və ən çox satılan məhsullar.",
                        Map.of("from", str(DATE), "to", str(DATE)), List.of()),
                new ToolSpec("list_sales",
                        "Dövr üzrə satışlar (çek nömrəsi, müştəri, məbləğ, ödəniş, qaytarma). Ən yenidən köhnəyə.",
                        Map.of("from", str(DATE), "to", str(DATE), "query", str("Çek nömrəsi və ya müştəri adı. Boş ola bilər.")),
                        List.of()),
                new ToolSpec("find_products",
                        "Anbardakı məhsullar: qalıq, vahid, alış və satış qiyməti, minimum qalıq. stock=LOW az qalanlar (bitənlər daxil), OUT bitənlər.",
                        Map.of("query", str("Məhsulun adı, kodu və ya barkodu. Boş ola bilər."),
                                "stock", en("Qalıq filtri", "LOW", "OUT")),
                        List.of()),
                new ToolSpec("inventory_summary",
                        "Anbarın xülasəsi: məhsul sayı, az qalan və bitən məhsul sayı, anbarın alış və satış qiymətilə dəyəri.",
                        Map.of(), List.of()),
                new ToolSpec("cash_accounts",
                        "Kassa və bank hesabları, hər birinin indiki qalığı.",
                        Map.of(), List.of()));
    }

    public ToolResult run(String name, Map<String, Object> in) {
        try {
            Object result = switch (name) {
                case "store_summary" -> dashboard.dashboard().summary();
                case "find_customers" -> parties.customers(s(in, "query"));
                case "find_companies" -> parties.companies(s(in, "query"));
                case "customer_profile" -> profiles.customer(uuid(in, "customer_id"));
                case "list_debts" -> debts.list(debtType(in), s(in, "status"), s(in, "query"), null, null, 0, MAX_ITEMS);
                case "sales_report" -> {
                    LocalDate from = date(in, "from", monthStart()), to = date(in, "to", tenant.today());
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("period", Map.of("from", from.toString(), "to", to.toString()));
                    r.put("profitAndLoss", profit.pnl(from, to));
                    r.put("topProducts", profit.topProducts(from, to, 10));
                    yield r;
                }
                case "list_sales" -> sales.list(date(in, "from", monthStart()), date(in, "to", tenant.today()),
                        s(in, "query"), null, null, 0, MAX_ITEMS);
                case "find_products" -> inventory.products(s(in, "query"), null, s(in, "stock"), true);
                case "inventory_summary" -> inventory.summary();
                case "cash_accounts" -> cash.accounts();
                default -> throw new IllegalArgumentException("Belə alət yoxdur: " + name);
            };
            return new ToolResult(serialize(result), false);
        } catch (ApiException e) {
            // e.g. PERMISSION_DENIED / PLAN_MODULE: the model tells the user they may not see this
            return new ToolResult(e.getCode() + ": " + e.getMessage(), true);
        } catch (IllegalArgumentException e) {
            return new ToolResult(e.getMessage(), true);
        }
    }

    private LocalDate monthStart() {
        return tenant.today().withDayOfMonth(1);
    }

    private static String s(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static UUID uuid(Map<String, Object> in, String key) {
        String v = s(in, key);
        try {
            return UUID.fromString(v);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(key + " düzgün id deyil");
        }
    }

    private static DebtType debtType(Map<String, Object> in) {
        String v = s(in, "type");
        try {
            return DebtType.valueOf(v == null ? "CUSTOMER_DEBT" : v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type yalnız CUSTOMER_DEBT, COMPANY_RECEIVABLE və ya COMPANY_PAYABLE ola bilər");
        }
    }

    private static LocalDate date(Map<String, Object> in, String key, LocalDate fallback) {
        String v = s(in, key);
        if (v == null) return fallback;
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " tarixi YYYY-MM-DD formatında olmalıdır");
        }
    }

    /** JSON for the model: long lists cut to MAX_ITEMS (with a note), the whole text to MAX_CHARS. */
    String serialize(Object value) {
        JsonNode node = json.valueToTree(value);
        trim(node);
        String text = node.toString();
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "…(qısaldıldı)";
    }

    private static void trim(JsonNode node) {
        if (node instanceof ArrayNode arr) {
            int total = arr.size();
            while (arr.size() > MAX_ITEMS) arr.remove(arr.size() - 1);
            arr.forEach(AssistantTools::trim);
            if (total > MAX_ITEMS) arr.addObject().put("_note", "cəmi " + total + " sətir, ilk " + MAX_ITEMS + " göstərilir");
        } else if (node instanceof ObjectNode obj) {
            List<String> drop = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isNull()) drop.add(e.getKey());
                else trim(e.getValue());
            }
            drop.forEach(obj::remove);
        }
    }
}
