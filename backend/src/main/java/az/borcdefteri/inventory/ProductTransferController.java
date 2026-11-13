package az.borcdefteri.inventory;

import az.borcdefteri.common.FileResponses;
import az.borcdefteri.reports.ProductExportService;
import az.borcdefteri.reports.ReportService.ExportFile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Anbar: all products as Excel / PDF, the import template, and Excel import. */
@RestController
@RequestMapping("/api/products")
public class ProductTransferController {

    private final ProductExportService exports;
    private final ProductImportService imports;

    public ProductTransferController(ProductExportService exports, ProductImportService imports) {
        this.exports = exports;
        this.imports = imports;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "xlsx") String format) {
        return attach(exports.export(format));
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template() {
        return attach(exports.template());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductImportService.Result importExcel(@RequestParam("file") MultipartFile file) {
        return imports.importExcel(file);
    }

    private static ResponseEntity<byte[]> attach(ExportFile f) {
        return FileResponses.attachment(f.content(), f.contentType(), f.fileName());
    }
}
