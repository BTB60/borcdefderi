package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stock counts (anbar sayımı): count document, per-line differences, booking on completion, the count report. */
class StockCountTest extends IntegrationTestBase {

    private String product(String token, String name, String buy, String stock) throws Exception {
        Map<String, Object> b = new HashMap<>(Map.of("name", name, "purchasePrice", buy, "salePrice", "9.00"));
        if (stock != null) b.put("initialStock", stock);
        return body(post(token, "/api/products", b).andExpect(status().isCreated())).get("id").asText();
    }

    private double stock(String token, String id) throws Exception {
        return body(get(token, "/api/products/" + id)).get("stockQty").asDouble();
    }

    private JsonNode line(JsonNode detail, String productId) {
        for (JsonNode l : detail.get("lines")) if (productId.equals(l.get("productId").asText())) return l;
        return null;
    }

    @Test
    void countSnapshotsDifferencesAndBooksThemOnCompletion() throws Exception {
        String owner = register("Sayım Bir");
        activateSubscription(owner, "MONTHLY");
        String a = product(owner, "Şəkər", "2.00", "10");
        String b = product(owner, "Düyü", "3.00", "5");
        String c = product(owner, "Yağ", "4.00", "8");
        String d = product(owner, "Duz", "1.00", "6");

        JsonNode count = body(post(owner, "/api/stock-counts", Map.of("note", "Ay sonu")).andExpect(status().isCreated()));
        String id = count.get("count").get("id").asText();
        assertTrue(count.get("count").get("number").asText().startsWith("SAY-"));
        assertEquals("IN_PROGRESS", count.get("count").get("status").asText());
        assertEquals(4, count.get("lines").size());
        assertEquals(10.0, line(count, a).get("systemQty").asDouble());
        assertFalse(line(count, a).hasNonNull("countedQty"));

        // only one open count per store
        post(owner, "/api/stock-counts", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("COUNT_IN_PROGRESS")));

        put(owner, "/api/stock-counts/" + id + "/lines/" + a, Map.of("countedQty", "12")).andExpect(status().isOk())
                .andExpect(jsonPath("$.difference", is(2.0))).andExpect(jsonPath("$.differenceValue", is(4.0)))
                .andExpect(jsonPath("$.countedByName", notNullValue()));
        put(owner, "/api/stock-counts/" + id + "/lines/" + b, Map.of("countedQty", "3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.difference", is(-2.0))).andExpect(jsonPath("$.differenceValue", is(-6.0)));
        put(owner, "/api/stock-counts/" + id + "/lines/" + c, Map.of("countedQty", "8")).andExpect(status().isOk())
                .andExpect(jsonPath("$.difference", is(0.0)));
        put(owner, "/api/stock-counts/" + id + "/lines/" + a, Map.of("countedQty", "-1")).andExpect(status().isBadRequest());

        // sold after it was counted: the difference stays counted − stock-at-count-time
        post(owner, "/api/inventory/movements", Map.of("productId", b, "kind", "ISSUE", "quantity", "1")).andExpect(status().isCreated());
        // a product added after the count began is added to the count when counted
        String e = product(owner, "Un", "1.50", "4");
        put(owner, "/api/stock-counts/" + id + "/lines/" + e, Map.of("countedQty", "4")).andExpect(status().isOk());

        JsonNode open = body(get(owner, "/api/stock-counts/" + id));
        JsonNode t = open.get("count").get("totals");
        assertEquals(5, t.get("totalItems").asInt());
        assertEquals(4, t.get("countedItems").asInt());
        assertEquals(2, t.get("matchedItems").asInt());
        assertEquals(1, t.get("surplusItems").asInt());
        assertEquals(1, t.get("shortageItems").asInt());
        assertEquals(4.0, t.get("surplusValue").asDouble());
        assertEquals(6.0, t.get("shortageValue").asDouble());
        assertEquals(-2.0, t.get("netValue").asDouble());
        assertEquals(10.0, stock(owner, a), "stock is untouched until the count is completed");

        JsonNode done = body(post(owner, "/api/stock-counts/" + id + "/complete", Map.of()).andExpect(status().isOk()));
        assertEquals("COMPLETED", done.get("count").get("status").asText());
        assertTrue(done.get("count").hasNonNull("completedByName"));
        assertEquals(12.0, stock(owner, a));
        assertEquals(2.0, stock(owner, b));   // 5 − 1 sold − 2 short
        assertEquals(8.0, stock(owner, c));
        assertEquals(6.0, stock(owner, d));   // not counted → untouched
        get(owner, "/api/inventory/movements?productId=" + a)
                .andExpect(jsonPath("$[0].kind", is("COUNT"))).andExpect(jsonPath("$[0].quantity", is(2.0)))
                .andExpect(jsonPath("$[0].note", startsWith("Sayım SAY-")));

        // closed for good
        put(owner, "/api/stock-counts/" + id + "/lines/" + d, Map.of("countedQty", "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("COUNT_CLOSED")));
        post(owner, "/api/stock-counts/" + id + "/complete", Map.of()).andExpect(status().isConflict());
        mvc.perform(delete("/api/stock-counts/" + id).header("Authorization", "Bearer " + owner)).andExpect(status().isConflict());

        // report
        get(owner, "/api/stock-counts").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].totals.shortageValue", is(6.0)));
        get(owner, "/api/stock-counts/" + id + "/export?format=xlsx").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml")));
        byte[] pdf = get(owner, "/api/stock-counts/" + id + "/export?format=pdf").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4));

        // a new count can begin now; an unfinished one can be discarded without touching stock
        String second = body(post(owner, "/api/stock-counts", Map.of()).andExpect(status().isCreated())).get("count").get("id").asText();
        put(owner, "/api/stock-counts/" + second + "/lines/" + d, Map.of("countedQty", "0")).andExpect(status().isOk());
        mvc.perform(delete("/api/stock-counts/" + second).header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        assertEquals(6.0, stock(owner, d));
        get(owner, "/api/stock-counts").andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void categoryScopeEmptyCountAndPermissions() throws Exception {
        String owner = register("Sayım İki");
        activateSubscription(owner, "MONTHLY");
        String cat = body(post(owner, "/api/product-categories", Map.of("name", "İçki")).andExpect(status().isCreated())).get("id").asText();
        Map<String, Object> drink = new HashMap<>(Map.of("name", "Su", "purchasePrice", "0.50", "salePrice", "1.00",
                "initialStock", "20", "categoryId", cat));
        post(owner, "/api/products", drink).andExpect(status().isCreated());
        product(owner, "Çörək", "0.40", "15");

        JsonNode count = body(post(owner, "/api/stock-counts", Map.of("categoryId", cat)).andExpect(status().isCreated()));
        assertEquals(1, count.get("lines").size());
        assertEquals("İçki", count.get("count").get("categoryName").asText());
        String id = count.get("count").get("id").asText();
        post(owner, "/api/stock-counts/" + id + "/complete", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("NOTHING_COUNTED")));

        // an employee without INVENTORY cannot see counts; another store cannot either
        String email = uniqueEmail("seller");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "Satıcı",
                "permissions", perms("SALES")));
        String seller = login(email, EMPLOYEE_PASSWORD);
        get(seller, "/api/stock-counts").andExpect(status().isForbidden());
        String other = register("Sayım Üç");
        activateSubscription(other, "MONTHLY");
        get(other, "/api/stock-counts/" + id).andExpect(status().isNotFound());
        assertEquals(List.of(), json.convertValue(body(get(other, "/api/stock-counts")), List.class));
    }
}
