package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin notes with follow-ups, store tags and the platform Excel export. */
class AdminCrmTest extends IntegrationTestBase {

    private String storeId(String name) throws Exception {
        return body(get(adminToken(), "/api/admin/stores?q=" + name)).get(0).get("id").asText();
    }

    @Test
    void notesFollowUpsAndTags() throws Exception {
        String owner = register("CRM Mağaza");
        String id = storeId("CRM Mağaza");

        JsonNode note = body(post(adminToken(), "/api/admin/stores/" + id + "/notes",
                Map.of("body", "Zəng etdim, sabah ödəyəcək", "followUpOn", java.time.LocalDate.now(clock).toString()))
                .andExpect(status().isCreated()));
        post(adminToken(), "/api/admin/stores/" + id + "/notes", Map.of("body", "Sadə qeyd")).andExpect(status().isCreated());
        assertThat(body(get(adminToken(), "/api/admin/stores/" + id + "/notes")).size()).isEqualTo(2);

        JsonNode due = body(get(adminToken(), "/api/admin/follow-ups"));
        assertThat(due.findValuesAsText("id")).contains(note.get("id").asText());
        assertThat(body(get(adminToken(), "/api/admin/stores?q=CRM Mağaza")).get(0).get("nextFollowUp").asText())
                .isEqualTo(java.time.LocalDate.now(clock).toString());

        post(adminToken(), "/api/admin/notes/" + note.get("id").asText() + "/done", Map.of("done", true)).andExpect(status().isOk());
        assertThat(body(get(adminToken(), "/api/admin/follow-ups")).findValuesAsText("id")).doesNotContain(note.get("id").asText());

        put(adminToken(), "/api/admin/stores/" + id + "/tags", Map.of("tags", List.of("VIP", " VIP ", "Zəng et", "")))
                .andExpect(status().isOk());
        JsonNode row = body(get(adminToken(), "/api/admin/stores?q=CRM Mağaza")).get(0);
        assertThat(row.get("tags").findValuesAsText("")).isEmpty();
        assertThat(row.get("tags").toString()).isEqualTo("[\"VIP\",\"Zəng et\"]");

        // stores never see or change admin notes
        get(owner, "/api/admin/stores/" + id + "/notes").andExpect(status().isForbidden());
        post(owner, "/api/admin/stores/" + id + "/notes", Map.of("body", "x")).andExpect(status().isForbidden());
        post(adminToken(), "/api/admin/stores/" + id + "/notes", Map.of("body", " ")).andExpect(status().isBadRequest());
    }

    @Test
    void exportsAnExcelWorkbook() throws Exception {
        String owner = register("İxrac Mağaza");
        activateSubscription(owner, "MONTHLY");
        byte[] xlsx = get(adminToken(), "/api/admin/export").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("spreadsheetml")))
                .andReturn().getResponse().getContentAsByteArray();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("Mağazalar").getPhysicalNumberOfRows()).isGreaterThan(1);
            assertThat(wb.getSheet("Ödənişlər").getPhysicalNumberOfRows()).isGreaterThan(1);
            assertThat(wb.getSheet("Aylıq gəlir").getPhysicalNumberOfRows()).isEqualTo(13);
        }
        get(owner, "/api/admin/export").andExpect(status().isForbidden());
    }
}
