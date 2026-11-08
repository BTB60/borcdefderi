package az.borcdefteri;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportExportTest extends IntegrationTestBase {

    @Test
    void debtExportsProduceExcelAndPdfFiles() throws Exception {
        String owner = register("Hesabat Mağazası");
        activateSubscription(owner, "SEMI_ANNUAL");
        UUID customer = createCustomer(owner, "Hesabat Müştəri", "+994501114400");
        debtIdOf(owner, "CUSTOMER_DEBT", customer, "77.00");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reports/debts/export").param("type", "CUSTOMER_DEBT").param("format", "xlsx")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));

        byte[] pdf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reports/debts/export").param("type", "CUSTOMER_DEBT").param("format", "pdf")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", is("application/pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertTrue(new String(pdf, 0, 4).startsWith("%PDF"));
    }

    @Test
    void ownerCanExportCompanyPayablesSection() throws Exception {
        String owner = register("Hesabat İcazə");
        activateSubscription(owner, "MONTHLY");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reports/debts/export").param("type", "COMPANY_PAYABLE").param("format", "pdf")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
    }
}
