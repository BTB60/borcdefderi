package az.borcdefteri.admin;

import az.borcdefteri.subscriptions.SubscriptionService;
import az.borcdefteri.subscriptions.SubscriptionService.PlanView;
import az.borcdefteri.subscriptions.SubscriptionService.ReceiptFile;
import az.borcdefteri.subscriptions.SubscriptionService.SubscriptionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platform (SUPER_ADMIN) endpoints. Protected by URL rule /api/admin/** and service-level checks. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record PlanUpdate(@NotBlank @Size(max = 100) String name,
                             @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
                             @Min(1) @Max(60) int durationMonths,
                             boolean active) {}

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ExtendRequest(@Min(1) @Max(366) int days) {}

    public record ActivateRequest(@NotBlank String planCode) {}

    private final AdminService admin;
    private final SubscriptionService subscriptions;

    public AdminController(AdminService admin, SubscriptionService subscriptions) {
        this.admin = admin;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/stores")
    public List<Map<String, Object>> stores(@RequestParam(required = false) String q) {
        return admin.stores(q);
    }

    @PostMapping("/stores/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable UUID id) {
        return admin.setStoreStatus(id, "SUSPENDED");
    }

    @PostMapping("/stores/{id}/activate")
    public Map<String, Object> activate(@PathVariable UUID id) {
        return admin.setStoreStatus(id, "ACTIVE");
    }

    @PostMapping("/stores/{id}/extend")
    public Map<String, LocalDate> extend(@PathVariable UUID id, @Valid @RequestBody ExtendRequest req) {
        return Map.of("endDate", subscriptions.extend(id, req.days()));
    }

    /** Activates (or renews) the store's subscription with the chosen package after the payment receipt is checked. */
    @PostMapping("/stores/{id}/subscription")
    public SubscriptionView activateSubscription(@PathVariable UUID id, @Valid @RequestBody ActivateRequest req) {
        return subscriptions.adminActivate(id, req.planCode());
    }

    @GetMapping("/subscriptions")
    public List<SubscriptionView> subscriptions(@RequestParam(required = false) String status) {
        return subscriptions.listByStatus(status);
    }

    @GetMapping("/subscriptions/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable UUID id) {
        ReceiptFile file = subscriptions.receiptFor(id);
        return az.borcdefteri.common.FileResponses.attachment(file.content(), file.contentType(), file.fileName());
    }

    @PostMapping("/subscriptions/{id}/approve")
    public SubscriptionView approve(@PathVariable UUID id) {
        return subscriptions.approve(id);
    }

    @PostMapping("/subscriptions/{id}/reject")
    public SubscriptionView reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest req) {
        return subscriptions.reject(id, req.reason());
    }

    @GetMapping("/plans")
    public List<PlanView> plans() {
        return subscriptions.allPlans();
    }

    @PutMapping("/plans/{id}")
    public PlanView updatePlan(@PathVariable UUID id, @Valid @RequestBody PlanUpdate req) {
        return subscriptions.updatePlan(id, req.name(), req.price(), req.durationMonths(), req.active());
    }

    @GetMapping("/revenue")
    public Map<String, Object> revenue() {
        return admin.revenue();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestParam(required = false) String q) {
        return admin.users(q);
    }

    @GetMapping("/audit-logs")
    public List<Map<String, Object>> auditLogs(@RequestParam(required = false) UUID storeId,
                                               @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        return admin.auditLogs(storeId, limit);
    }
}
