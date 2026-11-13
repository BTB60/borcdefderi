package az.borcdefteri.inventory;

import az.borcdefteri.common.FileResponses;
import az.borcdefteri.reports.ProductExportService;
import az.borcdefteri.reports.ProductLabelService;
import az.borcdefteri.reports.ReportService.ExportFile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Anbar: all products as Excel / PDF, the import template, Excel import and barcode labels. */
@RestController
@RequestMapping("/api/products")
public class ProductTransferController {

    private final ProductExportService exports;
    private final ProductImportService imports;
    private final ProductLabelService labels;

    public ProductTransferController(ProductExportService exports, ProductImportService imports, ProductLabelService labels) {
        this.exports = exports;
        this.imports = imports;
        this.labels = labels;
    }

    /** Barcode labels (PDF, one label per page). No ids = all active products. Opens inline for printing. */
    @GetMapping("/labels")
    public ResponseEntity<byte[]> labels(@RequestParam(required = false) List<UUID> ids,
                                         @RequestParam(defaultValue = "medium") String size,
                                         @RequestParam(defaultValue = "1") int copies,
                                         @RequestParam(defaultValue = "false") boolean perStock) {
        ExportFile f = labels.labels(ids, size, copies, perStock);
        return FileResponses.inline(f.content(), f.contentType(), f.fileName());
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
