package az.borcdefteri.subscriptions;

import az.borcdefteri.subscriptions.SubscriptionService.PlanView;
import az.borcdefteri.subscriptions.SubscriptionService.ReceiptFile;
import az.borcdefteri.subscriptions.SubscriptionService.SubscriptionView;
import az.borcdefteri.tenant.TenantService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
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
    private final TenantService tenant;

    public SubscriptionController(SubscriptionService subscriptions, TenantService tenant) {
        this.subscriptions = subscriptions;
        this.tenant = tenant;
    }

    @GetMapping("/api/public/plans")
    public List<PlanView> plans() {
        return subscriptions.activePlans();
    }

    @GetMapping("/api/subscriptions/current")
    public SubscriptionSummary current() {
        return subscriptions.summaryFor(tenant.requireMember().storeId());
    }

    @GetMapping("/api/subscriptions/history")
    public List<SubscriptionView> history() {
        return subscriptions.historyFor(tenant.requireMember().storeId());
    }

    public record PlanChoice(@jakarta.validation.constraints.NotBlank String planCode) {}

    /** "Abunə ol": records the chosen package as a pending request; payment is confirmed by the admin. */
    @PostMapping("/api/subscriptions/request")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionView request(@jakarta.validation.Valid @RequestBody PlanChoice req) {
        return subscriptions.request(req.planCode());
    }

    /** Optional: request with an uploaded receipt (PNG/JPG/PDF). */
    @PostMapping(value = "/api/subscriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionView submit(@RequestParam String planCode,
                                   @RequestParam(required = false) String payerReference,
                                   @RequestParam("receipt") MultipartFile receipt) {
        return subscriptions.submit(planCode, payerReference, receipt);
    }

    @GetMapping("/api/subscriptions/{id}/receipt")
    public ResponseEntity<FileSystemResource> receipt(@PathVariable UUID id) {
        ReceiptFile file = subscriptions.receiptFor(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header("Content-Disposition", ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file.path()));
    }
}
