package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IMEI / seriya: asked per unit at the till, kept with the sale, found again for the warranty. */
class SerialWarrantyTest extends IntegrationTestBase {

    @Test
    void phonesAreSoldWithImeiAndFoundForWarranty() throws Exception {
        String owner = register("Telefon Mağazası");
        activateSubscription(owner, "SEMI_ANNUAL");
        var customer = createCustomer(owner, "Zəmanət Müştərisi", "+994507654321");
        Map<String, Object> p = new HashMap<>(Map.of("name", "Telefon X", "purchasePrice", "300.00", "salePrice", "400.00",
                "initialStock", "5", "trackSerial", true, "warrantyMonths", 12));
        String phone = body(post(owner, "/api/products", p).andExpect(status().isCreated())
                .andExpect(jsonPath("$.trackSerial", is(true))).andExpect(jsonPath("$.warrantyMonths", is(12)))).get("id").asText();

        Map<String, Object> sale = new HashMap<>();
        sale.put("paidCash", "800.00");
        sale.put("customerId", customer.toString());
        // the till must send one IMEI per unit
        sale.put("items", List.of(Map.of("productId", phone, "quantity", "2", "serials", List.of("356938035643809"))));
        post(owner, "/api/sales", sale).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("SERIALS_REQUIRED")));
        sale.put("items", List.of(Map.of("productId", phone, "quantity", "2", "serials", List.of("356938035643809", "356938035643809"))));
        post(owner, "/api/sales", sale).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("SERIAL_DUPLICATE")));

        sale.put("items", List.of(Map.of("productId", phone, "quantity", "2", "serials", List.of("356938035643809", "356938035643817"))));
        post(owner, "/api/sales", sale).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].serials", contains("356938035643809", "356938035643817")))
                .andExpect(jsonPath("$.items[0].warrantyMonths", is(12)));

        // the same phone cannot be sold again
        sale.put("items", List.of(Map.of("productId", phone, "quantity", "1", "serials", List.of("356938035643809"))));
        post(owner, "/api/sales", sale).andExpect(status().isConflict()).andExpect(jsonPath("$.error", is("SERIAL_SOLD")));

        // warranty lookup by the full IMEI or its last digits
        LocalDate today = LocalDate.now(clock);
        get(owner, "/api/warranty?q=356938035643817").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].customerName", is("Zəmanət Müştərisi"))).andExpect(jsonPath("$[0].price", is(400.0)))
                .andExpect(jsonPath("$[0].warrantyUntil", is(today.plusMonths(12).toString()))).andExpect(jsonPath("$[0].inWarranty", is(true)));
        get(owner, "/api/warranty?q=3809").andExpect(jsonPath("$[0].serial", is("356938035643809")));
        get(owner, "/api/warranty?q=12").andExpect(status().isBadRequest());

        // the product can stop asking for IMEI (null keeps it, false switches it off)
        String version = body(get(owner, "/api/products/" + phone)).get("version").asText();
        Map<String, Object> upd = new HashMap<>(Map.of("name", "Telefon X", "purchasePrice", "300.00", "salePrice", "400.00",
                "version", Long.parseLong(version), "trackSerial", false, "warrantyMonths", 0));
        put(owner, "/api/products/" + phone, upd).andExpect(status().isOk())
                .andExpect(jsonPath("$.trackSerial", is(false))).andExpect(jsonPath("$.warrantyMonths").doesNotExist());
    }
}
