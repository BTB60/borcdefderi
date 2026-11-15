package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayOutputStream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Excel import of customers and companies with opening balances. */
class PartyImportTest extends IntegrationTestBase {

    private ResultActions upload(String token, String kind, byte[] xlsx) throws Exception {
        return mvc.perform(multipart("/api/" + kind + "/import")
                .file(new MockMultipartFile("file", "x.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                .header("Authorization", "Bearer " + token));
    }

    private static byte[] sheet(Object[]... rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("x");
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i);
                for (int j = 0; j < rows[i].length; j++) {
                    Object v = rows[i][j];
                    if (v instanceof Number n) r.createCell(j).setCellValue(n.doubleValue());
                    else if (v != null) r.createCell(j).setCellValue(v.toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void customersAreImportedWithTheirDebtAndNotTwice() throws Exception {
        String owner = register("İmport Mağaza");
        activateSubscription(owner, "MONTHLY");
        get(owner, "/api/customers/import-template").andExpect(status().isOk());
        byte[] file = sheet(new Object[]{"Ad", "Telefon", "Qeyd", "Borc"},
                new Object[]{"Əli Məmmədov", "050 111 22 33", "köhnə proqramdan", 150},
                new Object[]{"Leyla", null, null, null},
                new Object[]{null, null, null, null});
        upload(owner, "customers", file).andExpect(jsonPath("$.applied", is(true)))
                .andExpect(jsonPath("$.created", is(2))).andExpect(jsonPath("$.withBalance", is(1)));
        JsonNode list = body(get(owner, "/api/customers"));
        assertEquals(2, list.size());
        double total = 0;
        for (JsonNode c : list) total += c.get("openBalance").asDouble();
        assertEquals(150.0, total);

        // the same people again (phone written differently) are skipped
        byte[] again = sheet(new Object[]{"Ad", "Telefon", "Borc"}, new Object[]{"Əli M.", "+994501112233", 150}, new Object[]{"leyla", null, null});
        upload(owner, "customers", again).andExpect(jsonPath("$.created", is(0))).andExpect(jsonPath("$.skipped", is(2)));
    }

    @Test
    void aBadRowSavesNothing() throws Exception {
        String owner = register("İmport Səhv");
        activateSubscription(owner, "MONTHLY");
        byte[] file = sheet(new Object[]{"Ad", "Telefon", "Bizə borcu", "Bizim borcumuz"},
                new Object[]{"Yaxşı MMC", "012 555 00 00", 0, 1200},
                new Object[]{"Pis MMC", "abc", "on", null});
        upload(owner, "companies", file).andExpect(jsonPath("$.applied", is(false)))
                .andExpect(jsonPath("$.errors[0].row", is(3))).andExpect(jsonPath("$.errors[0].message", containsString("Telefon")));
        assertEquals(0, body(get(owner, "/api/companies")).size());

        byte[] good = sheet(new Object[]{"Ad", "Telefon", "Bizə borcu", "Bizim borcumuz"}, new Object[]{"Yaxşı MMC", "012 555 00 00", 0, 1200});
        upload(owner, "companies", good).andExpect(jsonPath("$.created", is(1))).andExpect(jsonPath("$.withBalance", is(1)));
        upload(owner, "customers", "not excel".getBytes()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_FILE")));
    }
}
