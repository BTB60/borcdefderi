package az.borcdefteri.customers;

import az.borcdefteri.common.FileResponses;
import az.borcdefteri.reports.ReportService.ExportFile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Excel import of customers and companies (with an optional opening balance). */
@RestController
public class PartyImportController {

    private final PartyImportService imports;

    public PartyImportController(PartyImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/api/customers/import-template")
    public ResponseEntity<byte[]> customerTemplate() {
        return attach(imports.template(true));
    }

    @GetMapping("/api/companies/import-template")
    public ResponseEntity<byte[]> companyTemplate() {
        return attach(imports.template(false));
    }

    @PostMapping(value = "/api/customers/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PartyImportService.Result importCustomers(@RequestParam("file") MultipartFile file) {
        return imports.importExcel(true, file);
    }

    @PostMapping(value = "/api/companies/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PartyImportService.Result importCompanies(@RequestParam("file") MultipartFile file) {
        return imports.importExcel(false, file);
    }

    private static ResponseEntity<byte[]> attach(ExportFile f) {
        return FileResponses.attachment(f.content(), f.contentType(), f.fileName());
    }
}
