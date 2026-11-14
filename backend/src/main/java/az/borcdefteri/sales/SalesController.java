package az.borcdefteri.sales;

import az.borcdefteri.common.FileResponses;
import az.borcdefteri.reports.ReportService.ExportFile;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SalesController {

    private final SalesService sales;
    private final SaleReceiptService receipts;
    private final ProfitService profit;

    public SalesController(SalesService sales, SaleReceiptService receipts, ProfitService profit) {
        this.sales = sales;
        this.receipts = receipts;
        this.profit = profit;
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    public SalesService.Sale create(@Valid @RequestBody SalesService.SaleRequest req) {
        return sales.create(req);
    }

    @GetMapping("/sales")
    public SalesService.SalePage list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return sales.list(from, to, q, customerId, status, page, size);
    }

    @GetMapping("/sales/{id}")
    public SalesService.Sale get(@PathVariable UUID id) {
        return sales.get(id);
    }

    @PostMapping("/sales/{id}/returns")
    public SalesService.Sale returnItems(@PathVariable UUID id, @Valid @RequestBody SalesService.ReturnRequest req) {
        return sales.returnItems(id, req);
    }

    @PostMapping("/sales/{id}/cancel")
    public SalesService.Sale cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) SalesService.CancelRequest req) {
        return sales.cancel(id, req);
    }

    @GetMapping("/sales/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable UUID id) {
        ExportFile f = receipts.receipt(id);
        return FileResponses.inline(f.content(), f.contentType(), f.fileName());
    }

    @GetMapping("/finance/pnl")
    public ProfitService.Pnl pnl(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return profit.pnl(from, to);
    }

    @GetMapping("/finance/monthly")
    public List<ProfitService.MonthPoint> monthly(@RequestParam(defaultValue = "12") int months) {
        return profit.monthly(months);
    }

    @GetMapping("/finance/top-products")
    public List<ProfitService.TopProduct> topProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit) {
        return profit.topProducts(from, to, limit);
    }

    @GetMapping("/finance/product-profit")
    public List<ProfitService.ProductProfit> productProfit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "100") int limit) {
        return profit.productProfit(from, to, limit);
    }
}
