package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reklam hekayələri: admin manages, every signed-in user sees only live stories, views and taps are counted. */
class AdStoryTest extends IntegrationTestBase {

    private Map<String, Object> story(String title) {
        Map<String, Object> b = new HashMap<>();
        b.put("title", title);
        b.put("subtitle", "Bu həftə 20% endirim");
        b.put("whatsapp", "+994 50 123 45 67");
        return b;
    }

    private void upload(String admin, String id) throws Exception {
        mvc.perform(multipart("/api/admin/ads/" + id + "/image")
                        .file(new MockMultipartFile("file", "ad.png", "image/png", pngBytes()))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasImage", is(true)));
    }

    @Test
    void adminPublishesStoresSeeOnlyLiveStories() throws Exception {
        String admin = adminToken();
        String owner = register("Reklam Bir");

        // only the platform admin manages stories
        post(owner, "/api/admin/ads", story("Hack")).andExpect(status().isForbidden());

        JsonNode a = body(post(admin, "/api/admin/ads", story("Vizit kartları -20%")).andExpect(status().isCreated()));
        String id = a.get("id").asText();
        assertEquals("WhatsApp-da yaz", a.get("buttonLabel").asText(), "a WhatsApp-only story gets a default button");
        assertEquals("+994501234567", a.get("whatsapp").asText());
        assertFalse(a.get("live").asBoolean(), "no picture yet: not live");

        // without a picture nobody sees it; with one it is live
        get(owner, "/api/ads").andExpect(jsonPath("$[?(@.id == '" + id + "')]", empty()));
        upload(admin, id);
        get(owner, "/api/ads").andExpect(jsonPath("$[?(@.id == '" + id + "')].title", contains("Vizit kartları -20%")))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].imageVersion", contains(1)));
        byte[] img = get(owner, "/api/ads/" + id + "/image").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(pngBytes(), img);

        // views and taps are counted for the admin
        post(owner, "/api/ads/" + id + "/view", Map.of()).andExpect(status().isNoContent());
        post(owner, "/api/ads/" + id + "/view", Map.of()).andExpect(status().isNoContent());
        post(owner, "/api/ads/" + id + "/click", Map.of()).andExpect(status().isNoContent());
        get(admin, "/api/admin/ads").andExpect(jsonPath("$[?(@.id == '" + id + "')].views", contains(2)))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].clicks", contains(1)));

        // switched off: gone for stores, its picture too, and views are no longer counted
        Map<String, Object> off = story("Vizit kartları -20%");
        off.put("active", false);
        put(admin, "/api/admin/ads/" + id, off).andExpect(status().isOk()).andExpect(jsonPath("$.live", is(false)));
        get(owner, "/api/ads").andExpect(jsonPath("$[?(@.id == '" + id + "')]", empty()));
        get(owner, "/api/ads/" + id + "/image").andExpect(status().isNotFound());
        get(admin, "/api/ads/" + id + "/image").andExpect(status().isOk());

        mvc.perform(delete("/api/admin/ads/" + id).header("Authorization", "Bearer " + admin)).andExpect(status().isNoContent());
        get(admin, "/api/admin/ads").andExpect(jsonPath("$[?(@.id == '" + id + "')]", empty()));
    }

    @Test
    void datesOrderAndValidation() throws Exception {
        String admin = adminToken();
        String owner = register("Reklam İki");

        Map<String, Object> later = story("Gələcək");
        later.put("startsOn", "2099-01-01");
        String future = body(post(admin, "/api/admin/ads", later)).get("id").asText();
        upload(admin, future);
        Map<String, Object> ended = story("Bitib");
        ended.put("endsOn", "2000-01-01");
        String past = body(post(admin, "/api/admin/ads", ended)).get("id").asText();
        upload(admin, past);
        Map<String, Object> first = story("Birinci");
        first.put("sortOrder", -500);
        first.put("linkUrl", "https://premiumreklam.az");
        JsonNode f = body(post(admin, "/api/admin/ads", first));
        assertEquals("Ətraflı", f.get("buttonLabel").asText(), "a story with a link gets the link button");
        upload(admin, f.get("id").asText());

        get(owner, "/api/ads")
                .andExpect(jsonPath("$[?(@.id == '" + future + "')]", empty()))
                .andExpect(jsonPath("$[?(@.id == '" + past + "')]", empty()))
                .andExpect(jsonPath("$[0].title", is("Birinci")));
        post(owner, "/api/ads/" + past + "/view", Map.of()).andExpect(status().isNoContent());
        get(admin, "/api/admin/ads").andExpect(jsonPath("$[?(@.id == '" + past + "')].views", contains(0)));

        Map<String, Object> bad = story("Pis link");
        bad.put("linkUrl", "javascript:alert(1)");
        post(admin, "/api/admin/ads", bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_LINK")));
        Map<String, Object> badWa = story("Pis nömrə");
        badWa.put("whatsapp", "zəng edin");
        post(admin, "/api/admin/ads", badWa).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_WHATSAPP")));
        Map<String, Object> badDates = story("Tarix");
        badDates.put("startsOn", "2030-02-01");
        badDates.put("endsOn", "2030-01-01");
        post(admin, "/api/admin/ads", badDates).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_DATES")));
        post(admin, "/api/admin/ads", Map.of("title", " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_TITLE")));
    }
}
