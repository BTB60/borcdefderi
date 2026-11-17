package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tədarükçünün qiymət siyahısı: preview, then apply, matching by barcode / code / name. */
class PriceListTest extends IntegrationTestBase {

    private byte[] sheet(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var s = wb.createSheet("Qiymətlər");
            s.createRow(0).createCell(0).setCellValue("Təchizatçı MMC qiymət siyahısı");
            for (int i = 0; i < rows.length; i++) {
                var r = s.createRow(i + 2);
                for (int j = 0; j < rows[i].length; j++) {
                    if (rows[i][j] instanceof Number n) r.createCell(j).setCellValue(n.doubleValue());
                    else if (rows[i][j] != null) r.createCell(j).setCellValue(rows[i][j].toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private JsonNode upload(String token, byte[] file, boolean apply) throws Exception {
        return body(mvc.perform(multipart("/api/products/price-list")
                        .file(new MockMultipartFile("file", "q.xlsx", "application/octet-stream", file))
                        .param("apply", String.valueOf(apply)).param("keepMargin", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
    }

    @Test
    void pricesAreMatchedPreviewedAndApplied() throws Exception {
        String owner = register("Qiymət Siyahısı");
        activateSubscription(owner, "MONTHLY");
        String tea = body(post(owner, "/api/products", Map.of("name", "Çay Azərçay", "barcode", "4760000000011",
                "purchasePrice", "4.00", "salePrice", "6.00")).andExpect(status().isCreated())).get("id").asText();
        body(post(owner, "/api/products", Map.of("name", "Şəkər 1 kq", "purchasePrice", "1.50", "salePrice", "2.00"))
                .andExpect(status().isCreated()));
        byte[] file = sheet(new Object[][]{
                {"Barkod", "Ad", "Qiymət"},
                {4760000000011L, "Azərçay 100q", 5.00},
                {null, "şəkər 1 kq", "1,50"},
                {"999", "Naməlum mal", 3},
        });

        JsonNode preview = upload(owner, file, false);
        assertThat(preview.get("changed").asInt()).isEqualTo(1);
        assertThat(preview.get("same").asInt()).isEqualTo(1);
        assertThat(preview.get("notFound").asInt()).isEqualTo(1);
        assertThat(preview.get("lines").get(0).get("newSalePrice").asDouble()).isEqualTo(7.5);
        assertThat(body(get(owner, "/api/products/" + tea)).get("purchasePrice").asDouble()).isEqualTo(4.0);

        upload(owner, file, true);
        JsonNode p = body(get(owner, "/api/products/" + tea));
        assertThat(p.get("purchasePrice").asDouble()).isEqualTo(5.0);
        assertThat(p.get("salePrice").asDouble()).isEqualTo(7.5);
    }
}
