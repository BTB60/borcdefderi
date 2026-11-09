package az.borcdefteri.subscriptions;

import az.borcdefteri.subscriptions.PlanService.PlanView;
import az.borcdefteri.subscriptions.SubscriptionService.PaymentClaim;
import az.borcdefteri.subscriptions.SubscriptionService.ReceiptFile;
import az.borcdefteri.subscriptions.SubscriptionService.SubscriptionView;
import az.borcdefteri.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
public class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final PlanService plans;
    private final TenantService tenant;

    public SubscriptionController(SubscriptionService subscriptions, PlanService plans, TenantService tenant) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.tenant = tenant;
    }

    /** Active packages with their period prices, advantages and colours (public: also shown before login). */
    @GetMapping("/api/public/plans")
    public List<PlanView> plans() {
        return plans.activePlans();
    }

    @GetMapping("/api/subscriptions/current")
    public SubscriptionSummary current() {
        return subscriptions.summaryFor(tenant.requireMember().storeId());
    }

    @GetMapping("/api/subscriptions/history")
    public List<SubscriptionView> history() {
        return subscriptions.historyFor(tenant.requireMember().storeId());
    }

    public record PlanChoice(@jakarta.validation.constraints.NotBlank String planCode,
                             @jakarta.validation.constraints.Size(max = 120) String payerReference,
                             String period, String paymentMethod,
                             @jakarta.validation.constraints.Size(max = 120) String payerName,
                             java.math.BigDecimal paidAmount, java.time.LocalDate paymentDate,
                             @jakarta.validation.constraints.Size(max = 500) String note) {}

    /** "Ödəniş etdim" without a file. Creates or updates the store's pending payment request. */
    @PostMapping("/api/subscriptions/request")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionView request(@jakarta.validation.Valid @RequestBody PlanChoice req) {
        return subscriptions.request(new PaymentClaim(req.planCode(), req.period(), req.paymentMethod(),
                req.payerName(), req.paidAmount(), req.paymentDate(), req.note(), req.payerReference()), null);
    }

    /** "Ödəniş etdim" with an optional receipt image (PNG/JPG/PDF, max 5 MB, checked by content). */
    @PostMapping(value = "/api/subscriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionView submit(@RequestParam String planCode,
                                   @RequestParam(required = false) String payerReference,
                                   @RequestParam(required = false) String period,
                                   @RequestParam(required = false) String paymentMethod,
                                   @RequestParam(required = false) String payerName,
                                   @RequestParam(required = false) java.math.BigDecimal paidAmount,
                                   @RequestParam(required = false) java.time.LocalDate paymentDate,
                                   @RequestParam(required = false) String note,
                                   @RequestParam(value = "receipt", required = false) MultipartFile receipt) {
        return subscriptions.request(new PaymentClaim(planCode, period, paymentMethod, payerName, paidAmount,
                paymentDate, note, payerReference), receipt);
    }

    @GetMapping("/api/subscriptions/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable UUID id) {
        ReceiptFile file = subscriptions.receiptFor(id);
        return az.borcdefteri.common.FileResponses.attachment(file.content(), file.contentType(), file.fileName());
    }
}
