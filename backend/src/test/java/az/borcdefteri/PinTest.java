package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kassir PIN: set it, switch the till to the worker by PIN, wrong PINs are refused. */
class PinTest extends IntegrationTestBase {

    @Test
    void theOwnerTakesOverTheTillWithAPin() throws Exception {
        String owner = register("PIN Mağaza");
        activateSubscription(owner, "MONTHLY");
        put(owner, "/api/profile/pin", Map.of("pin", "12", "currentPassword", OWNER_PASSWORD)).andExpect(status().isBadRequest());
        put(owner, "/api/profile/pin", Map.of("pin", "4321", "currentPassword", "yanlis")).andExpect(status().isBadRequest());
        put(owner, "/api/profile/pin", Map.of("pin", "4321", "currentPassword", OWNER_PASSWORD)).andExpect(status().isOk());
        assertThat(body(get(owner, "/api/profile/pin")).get("hasPin").asBoolean()).isTrue();

        JsonNode sw = body(post(owner, "/api/till/pin-switch", Map.of("pin", "4321")).andExpect(status().isOk()));
        assertThat(sw.get("token").asText()).isNotBlank();
        assertThat(sw.get("me").get("fullName").asText()).isEqualTo("Sahib PIN Mağaza");
        post(owner, "/api/till/pin-switch", Map.of("pin", "9999")).andExpect(status().isBadRequest());

        // another store's PIN does not open this till
        String other = register("Başqa PIN");
        activateSubscription(other, "MONTHLY");
        post(other, "/api/till/pin-switch", Map.of("pin", "4321")).andExpect(status().isBadRequest());
    }

    @Test
    void barcodePicturesForLabelPrinters() throws Exception {
        byte[] png = get(null, "/api/public/barcode.png?text=4760000000011").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(png[1]).isEqualTo((byte) 'P');
        get(null, "/api/public/barcode.png?text=P-000123").andExpect(status().isOk());
        get(null, "/api/public/barcode.png?text=<script>").andExpect(status().isBadRequest());
    }
}
