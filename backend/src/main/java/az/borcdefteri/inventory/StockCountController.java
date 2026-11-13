package az.borcdefteri.inventory;

import az.borcdefteri.common.FileResponses;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.reports.StockCountReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stock counts (anbar sayımı) and their report. */
@RestController
@RequestMapping("/api/stock-counts")
public class StockCountController {

    private final StockCountService counts;
    private final StockCountReportService reports;

    public StockCountController(StockCountService counts, StockCountReportService reports) {
        this.counts = counts;
        this.reports = reports;
    }

    @GetMapping
    public List<StockCountService.CountSummary> list(@RequestParam(defaultValue = "100") int limit) {
        return counts.list(limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StockCountService.CountDetail start(@Valid @RequestBody(required = false) StockCountService.StartRequest req) {
        return counts.start(req);
    }

    @GetMapping("/{id}")
    public StockCountService.CountDetail detail(@PathVariable UUID id) {
        return counts.detail(id);
    }

    @PutMapping("/{id}/lines/{productId}")
    public StockCountService.Line count(@PathVariable UUID id, @PathVariable UUID productId,
                                        @Valid @RequestBody StockCountService.LineRequest req) {
        return counts.count(id, productId, req);
    }

    @PostMapping("/{id}/complete")
    public StockCountService.CountDetail complete(@PathVariable UUID id) {
        return counts.complete(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        counts.delete(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam(defaultValue = "xlsx") String format) {
        ExportFile f = reports.export(id, format);
        return FileResponses.attachment(f.content(), f.contentType(), f.fileName());
    }
}
