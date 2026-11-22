package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Search finds Azerbaijani names whatever the case and the database locale (the tests run on a "C" locale, where
 * plain LOWER() leaves Ə, Ş, İ… unchanged): from the first letter, as the lists search while typing.
 */
class SearchNormTest extends IntegrationTestBase {

    private List<String> names(String token, String path, String q) throws Exception {
        JsonNode r = body(get(token, path + "?q=" + q).andExpect(status().isOk()));
        List<String> out = new ArrayList<>();
        r.forEach(n -> out.add(n.get("name").asText()));
        return out.stream().sorted().toList();
    }

    @Test
    void azerbaijaniLettersMatchInAnyCase() throws Exception {
        String owner = register("Axtarış Mağazası");
        activateSubscription(owner, "MONTHLY");
        createCustomer(owner, "Əli Məmmədov", "+994501230001");
        createCustomer(owner, "Murad Əliyev", "+994501230002");
        createCustomer(owner, "İlham Şükürov", "+994501230003");
        createCustomer(owner, "Elnur Həsənov", "+994501230004");

        assertEquals(List.of("Murad Əliyev", "Əli Məmmədov"), names(owner, "/api/customers", "əli"));
        assertEquals(List.of("Murad Əliyev", "Əli Məmmədov"), names(owner, "/api/customers", "ƏLİ"));
        assertEquals(List.of("İlham Şükürov"), names(owner, "/api/customers", "ilham"));
        assertEquals(List.of("İlham Şükürov"), names(owner, "/api/customers", "şük"));
        assertEquals(List.of("İlham Şükürov"), names(owner, "/api/customers", "Ş"));
        assertEquals(4, names(owner, "/api/customers", "").size());
        // the user's % and _ are still plain characters
        assertEquals(List.of(), names(owner, "/api/customers", "%"));

        for (String n : List.of("IŞIQ LAMPA 60W", "Çay Azərçay 100 q", "Молоко 1 л")) {
            post(owner, "/api/products", Map.of("name", n, "purchasePrice", "1.00", "salePrice", "2.00")).andExpect(status().isCreated());
        }
        assertEquals(List.of("IŞIQ LAMPA 60W"), names(owner, "/api/products", "işıq"));
        assertEquals(List.of("IŞIQ LAMPA 60W"), names(owner, "/api/products", "işiq"));
        assertEquals(List.of("Çay Azərçay 100 q"), names(owner, "/api/products", "azərç"));
        assertEquals(List.of("Молоко 1 л"), names(owner, "/api/products", "молоко"));
    }
}
