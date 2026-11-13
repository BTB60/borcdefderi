package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anbar products: Excel / PDF export, and Excel import (create new, update existing, reject a bad file whole). */
class ProductTransferTest extends IntegrationTestBase {

    private String product(String token, String name, String buy, String sell, String stock) throws Exception {
        Map<String, Object> b = new HashMap<>(Map.of("name", name, "purchasePrice", buy, "salePrice", sell));
        if (stock != null) b.put("initialStock", stock);
        return body(post(token, "/api/products", b).andExpect(status().isCreated())).get("id").asText();
    }

    private ResultActions upload(String token, byte[] xlsx) throws Exception {
        return mvc.perform(multipart("/api/products/import")
                .file(new MockMultipartFile("file", "mehsullar.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                .header("Authorization", "Bearer " + token));
    }

    private static byte[] write(Workbook wb) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void exportEditImportRoundTrip() throws Exception {
        String owner = register("Import Bir");
        activateSubscription(owner, "MONTHLY");
        String sugar = product(owner, "Şəkər", "1.60", "2.20", "40");
        product(owner, "Düyü", "2.80", "3.60", "25");

        byte[] pdf = get(owner, "/api/products/export?format=pdf").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4));
        byte[] xlsx = get(owner, "/api/products/export?format=xlsx").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.getSheetAt(0);
            assertEquals("Kod", s.getRow(0).getCell(0).getStringCellValue());
            assertEquals(2, s.getLastRowNum());
            // raise the sale price of Şəkər, change its stock (ignored for existing products)
            for (int i = 1; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                if ("Şəkər".equals(r.getCell(2).getStringCellValue())) {
                    r.getCell(6).setCellValue(2.50);
                    r.getCell(7).setCellValue(999);
                }
            }
            // a new product with a new category and a numeric barcode
            Row n = s.createRow(3);
            n.createCell(0).setCellValue("");
            n.createCell(1).setCellValue(4760000000059d);
            n.createCell(2).setCellValue("Su 0.5 l");
            n.createCell(3).setCellValue("İçkilər");
            n.createCell(4).setCellValue("ədəd");
            n.createCell(5).setCellValue("0,25");
            n.createCell(6).setCellValue(0.5);
            n.createCell(7).setCellValue(120);
            n.createCell(8).setCellValue(10);
            n.createCell(9).setCellValue("Bəli");
            xlsx = write(wb);
        }

        upload(owner, xlsx).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied", is(true)))
                .andExpect(jsonPath("$.created", is(1)))
                .andExpect(jsonPath("$.updated", is(1)))
                .andExpect(jsonPath("$.unchanged", is(1)));

        JsonNode s = body(get(owner, "/api/products/" + sugar));
        assertEquals(2.5, s.get("salePrice").asDouble());
        assertEquals(40.0, s.get("stockQty").asDouble(), "an import does not change the stock of existing products");
        JsonNode water = body(get(owner, "/api/products/by-code/4760000000059"));
        assertEquals("Su 0.5 l", water.get("name").asText());
        assertEquals("İçkilər", water.get("categoryName").asText());
        assertEquals(120.0, water.get("stockQty").asDouble());
        assertEquals(0.25, water.get("purchasePrice").asDouble());

        // the same file again changes nothing
        upload(owner, xlsx).andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(0))).andExpect(jsonPath("$.updated", is(0)));
        get(owner, "/api/products").andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void badRowsRejectTheWholeFile() throws Exception {
        String owner = register("Import İki");
        activateSubscription(owner, "MONTHLY");
        byte[] template = get(owner, "/api/products/import-template").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        byte[] xlsx;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            Sheet s = wb.getSheetAt(0);
            Row bad = s.createRow(2);
            bad.createCell(2).setCellValue("Çay");
            bad.createCell(5).setCellValue("abc");
            Row dup = s.createRow(3);
            dup.createCell(1).setCellValue("4760000000011");
            dup.createCell(2).setCellValue("Şəkər 2");
            dup.createCell(5).setCellValue(1);
            dup.createCell(6).setCellValue(2);
            xlsx = write(wb);
        }
        upload(owner, xlsx).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied", is(false)))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].row", is(3)));
        get(owner, "/api/products").andExpect(jsonPath("$", hasSize(0)));

        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("x").createRow(0).createCell(0).setCellValue("Məhsul");
            upload(owner, write(wb)).andExpect(jsonPath("$.applied", is(false)))
                    .andExpect(jsonPath("$.errors[0].message", containsString("Ad")));
        }
        upload(owner, "not excel".getBytes()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_FILE")));
    }
}
