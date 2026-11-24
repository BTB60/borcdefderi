package az.borcdefteri;

import az.borcdefteri.assistant.CardDesignAi;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Base64;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Bonus Kart Studio: designs per store, the AI designer behind its switch and limit, QR and the print PDF. */
class CardStudioTest extends IntegrationTestBase {

    @MockitoBean
    CardDesignAi ai;

    @Test
    void designsStayInTheirStoreAndTheAiIsGuarded() throws Exception {
        String a = register("Studio A");
        activateSubscription(a, "MONTHLY");
        String b = register("Studio B");
        activateSubscription(b, "MONTHLY");

        JsonNode d = body(post(a, "/api/card-studio/designs", Map.of("name", "Qızıl VIP", "category", "vip",
                "spec", Map.of("color1", "#111111", "title", "BONUS KART"))).andExpect(status().isCreated()));
        get(a, "/api/card-studio/designs").andExpect(jsonPath("$.length()", is(1))).andExpect(jsonPath("$[0].spec.title", is("BONUS KART")));
        get(b, "/api/card-studio/designs").andExpect(jsonPath("$.length()", is(0)));
        put(b, "/api/card-studio/designs/" + d.get("id").asText(), Map.of("name", "x", "spec", Map.of())).andExpect(status().isNotFound());
        put(a, "/api/card-studio/designs/" + d.get("id").asText(), Map.of("name", "Qızıl VIP 2", "spec", Map.of("color1", "#222222")))
                .andExpect(jsonPath("$.name", is("Qızıl VIP 2")));
        post(a, "/api/card-studio/designs", Map.of("name", "boş", "spec", "salam")).andExpect(status().isBadRequest());

        // AI off: the studio says so (the page then uses its built-in generator)
        when(ai.configured()).thenReturn(false);
        get(a, "/api/card-studio/status").andExpect(jsonPath("$.ai", is(false)));
        post(a, "/api/card-studio/generate", Map.of("category", "cafe")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("AI_OFF")));

        // AI on: a design comes back and is counted per store and day
        when(ai.configured()).thenReturn(true);
        CardDesignAi.AiCard card = new CardDesignAi.AiCard("Qəhvə dostu", "dots", "#3E2723", "#8D6E63", "#FFCC80", "#FFFFFF",
                "serif", "classic", "cup", "Hər fincana bonus", "<circle cx=\"120\" cy=\"120\" r=\"80\" fill=\"#fff\"/>");
        when(ai.design(anyString(), any(), anyString())).thenReturn(new CardDesignAi.Result(card, 1200, 3400));
        post(a, "/api/card-studio/generate", Map.of("category", "cafe", "idea", "qəhvə")).andExpect(status().isOk())
                .andExpect(jsonPath("$.card.name", is("Qəhvə dostu"))).andExpect(jsonPath("$.usedToday", is(1)));
        get(a, "/api/card-studio/status").andExpect(jsonPath("$.usedToday", is(1)));
        get(b, "/api/card-studio/status").andExpect(jsonPath("$.usedToday", is(0)));

        mvc.perform(MockMvcRequestBuilders.delete("/api/card-studio/designs/" + d.get("id").asText()).header("Authorization", "Bearer " + a))
                .andExpect(status().isNoContent());
    }

    @Test
    void theAiCharacterIsCheckedBeforeItReachesThePage() {
        assertNotNull(CardDesignAiAccess.safe("<defs><radialGradient id=\"ca\"><stop offset=\"0\" stop-color=\"#fff\"/></radialGradient></defs><circle cx=\"1\" cy=\"1\" r=\"1\" fill=\"url(#ca)\"/>"));
        assertNull(CardDesignAiAccess.safe("<script>alert(1)</script>"));
        assertNull(CardDesignAiAccess.safe("<circle onload=\"x()\" r=\"1\"/>"));
        assertNull(CardDesignAiAccess.safe("<image href=\"https://x\"/>"));
        assertNull(CardDesignAiAccess.safe("<foreignObject></foreignObject>"));
        assertNull(CardDesignAiAccess.safe("<rect fill=\"url(https://evil)\"/>"));
    }

    @Test
    void qrAndPrintPdf() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/public/qr.svg").param("text", "HP-000123"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("<svg")));
        String owner = register("Studio PDF");
        activateSubscription(owner, "MONTHLY");
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(108, 71, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bytes);
        String png = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        mvc.perform(MockMvcRequestBuilders.post("/api/card-studio/pdf").header("Authorization", "Bearer " + owner).contentType("application/json")
                .content(json.writeValueAsString(Map.of("front", "data:image/png;base64,AAAA", "back", png)))).andExpect(status().isBadRequest());
        for (boolean cmyk : new boolean[]{false, true}) {
            byte[] pdf = mvc.perform(MockMvcRequestBuilders.post("/api/card-studio/pdf").header("Authorization", "Bearer " + owner)
                            .contentType("application/json").content(json.writeValueAsString(Map.of("front", png, "back", png, "cmyk", cmyk))))
                    .andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
            assertTrue(new String(pdf, 0, 5).startsWith("%PDF"));
            assertTrue(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1).contains("/TrimBox"));
        }
    }
}
