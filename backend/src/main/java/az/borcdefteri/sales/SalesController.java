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
    private final az.borcdefteri.payments.ReceiptLinks links;
    private final ReturnApprovalService approvals;

    public record ApprovalPolicyUpdate(boolean enabled,
                                       @jakarta.validation.constraints.DecimalMin("0.00")
                                       @jakarta.validation.constraints.Digits(integer = 12, fraction = 2) java.math.BigDecimal limit) {}

    public record ApprovalPinUpdate(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 6) String pin,
                                    @jakarta.validation.constraints.Size(max = 72) String currentPassword) {}

    public SalesController(SalesService sales, SaleReceiptService receipts, ProfitService profit,
                           az.borcdefteri.payments.ReceiptLinks links, ReturnApprovalService approvals) {
        this.links = links;
        this.sales = sales;
        this.receipts = receipts;
        this.profit = profit;
        this.approvals = approvals;
    }

    @GetMapping("/return-approval")
    public ReturnApprovalService.Policy approvalPolicy() {
        return approvals.policy();
    }

    @PutMapping("/return-approval/policy")
    public ReturnApprovalService.Policy updateApprovalPolicy(@Valid @RequestBody ApprovalPolicyUpdate req) {
        return approvals.updatePolicy(req.enabled(), req.limit());
    }

    @PutMapping("/return-approval/pin")
    public ReturnApprovalService.Policy setApprovalPin(@Valid @RequestBody ApprovalPinUpdate req) {
        return approvals.setPin(req.pin(), req.currentPassword());
    }

    public record ApprovalCardRequest(@jakarta.validation.constraints.Size(max = 72) String currentPassword) {}

    /** Issues a new manager card (the old one stops working) and returns it as a PDF to print. */
    @PostMapping("/return-approval/card")
    public ResponseEntity<byte[]> issueApprovalCard(@Valid @RequestBody ApprovalCardRequest req) {
        ExportFile f = approvals.issueCard(req.currentPassword());
        return FileResponses.attachment(f.content(), f.contentType(), f.fileName());
    }

    @DeleteMapping("/return-approval/card")
    public ReturnApprovalService.Policy revokeApprovalCard() {
        return approvals.revokeCard();
    }

    @DeleteMapping("/return-approval/pin")
    public ReturnApprovalService.Policy clearApprovalPin() {
        return approvals.clearPin();
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

    /** A link to this sale's receipt that the customer can open without signing in (sent over WhatsApp / SMS). */
    @GetMapping("/sales/{id}/share-link")
    public java.util.Map<String, String> shareLink(@PathVariable UUID id) {
        sales.get(id); // access check: the caller's store and the SALES permission
        return java.util.Map.of("url", links.saleUrl(id));
    }

    @GetMapping("/public/sales/{id}/{token}")
    public ResponseEntity<byte[]> sharedReceipt(@PathVariable UUID id, @PathVariable String token) {
        if (!links.isValidSale(id, token)) throw az.borcdefteri.common.ApiException.notFound("Çek tapılmadı");
        ExportFile f = receipts.sharedReceipt(id);
        return FileResponses.inline(f.content(), f.contentType(), f.fileName());
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
