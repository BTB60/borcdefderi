package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Çek və barkod dizaynı is kept per store: everyone reads it, the owner / TILL_SETTINGS changes it. */
class PrintDesignTest extends IntegrationTestBase {

    @Test
    void theStoresDesignIsSharedAndOnlyTheOwnerOrTillSettingsChangesIt() throws Exception {
        String owner = register("Dizayn Mağaza");
        activateSubscription(owner, "MONTHLY");
        get(owner, "/api/settings/print-design").andExpect(status().isOk()).andExpect(jsonPath("$.receipt").doesNotExist());

        put(owner, "/api/settings/print-design", Map.of("receipt", Map.of("paper", "58", "header", "Tel: 050 123 45 67", "footer", "Sağ olun")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receipt.paper", is("58")));
        put(owner, "/api/settings/print-design", Map.of("label", Map.of("w", 40, "h", 25, "show", List.of("name", "price"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.footer", is("Sağ olun"))) // the other part is kept
                .andExpect(jsonPath("$.label.w", is(40)));

        String sellerEmail = uniqueEmail("seller");
        post(owner, "/api/employees", Map.of("email", sellerEmail, "password", EMPLOYEE_PASSWORD, "fullName", "Satıcı",
                "permissions", perms("SALES"))).andExpect(status().isCreated());
        String seller = login(sellerEmail, EMPLOYEE_PASSWORD);
        get(seller, "/api/settings/print-design").andExpect(jsonPath("$.receipt.header", is("Tel: 050 123 45 67")));
        put(seller, "/api/settings/print-design", Map.of("receipt", Map.of("paper", "80"))).andExpect(status().isForbidden());

        String tillEmail = uniqueEmail("till");
        post(owner, "/api/employees", Map.of("email", tillEmail, "password", EMPLOYEE_PASSWORD, "fullName", "Böyük kassir",
                "permissions", perms("SALES", "TILL_SETTINGS"))).andExpect(status().isCreated());
        String till = login(tillEmail, EMPLOYEE_PASSWORD);
        // {"receipt": null} resets that part (raw JSON: the test mapper leaves nulls out)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings/print-design")
                        .header("Authorization", "Bearer " + till).contentType("application/json").content("{\"receipt\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt").doesNotExist()).andExpect(jsonPath("$.label.h", is(25)));

        put(owner, "/api/settings/print-design", Map.of("receipt", "x")).andExpect(status().isBadRequest());
        put(owner, "/api/settings/print-design", Map.of("receipt", Map.of("logo", "a".repeat(1_600_000)))).andExpect(status().isBadRequest());

        // the receipt PDF follows the design (narrow paper, own header, logo, no cashier), also with a broken logo
        String png = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="));
        String p = body(post(owner, "/api/products", new HashMap<>(Map.of("name", "Su", "purchasePrice", "0.5", "salePrice", "1.00",
                "initialStock", "10")))).get("id").asText();
        String sale = body(post(owner, "/api/sales", Map.of("items", List.of(Map.of("productId", p, "quantity", "2")), "paidCash", "2.00"))
                .andExpect(status().isCreated())).get("id").asText();
        for (String logo : new String[]{png, "data:image/png;base64,@@@", ""}) {
            put(owner, "/api/settings/print-design", Map.of("receipt", Map.of("paper", "58", "header", "Tel: 050 123 45 67\nBakı",
                    "footer", "Sağ olun\nYenə gəlin", "logo", logo, "fontSize", "l", "align", "left", "showCashier", false))).andExpect(status().isOk());
            org.springframework.test.web.servlet.MvcResult r = get(owner, "/api/sales/" + sale + "/receipt").andExpect(status().isOk()).andReturn();
            org.junit.jupiter.api.Assertions.assertTrue(new String(r.getResponse().getContentAsByteArray(), 0, 5).startsWith("%PDF"));
        }

        // another store sees nothing of it
        String other = register("Başqa Mağaza");
        activateSubscription(other, "MONTHLY");
        get(other, "/api/settings/print-design").andExpect(jsonPath("$.label").doesNotExist());
    }
}
