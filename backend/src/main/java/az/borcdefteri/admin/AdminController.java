package az.borcdefteri.admin;

import az.borcdefteri.subscriptions.SubscriptionService;
import az.borcdefteri.subscriptions.PlanService;
import az.borcdefteri.subscriptions.PlanService.PlanInput;
import az.borcdefteri.subscriptions.PlanService.PlanView;
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

    /**
     * Package create / update. New admin form: period prices, features, limits, colours (see {@link PlanInput}).
     * Older clients may still send only name + price + durationMonths + active.
     */
    public record PlanUpdate(@NotBlank @Size(max = 100) String name,
                             @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
                             @Min(1) @Max(60) Integer durationMonths,
                             Boolean active,
                             @Size(max = 500) String description,
                             @Size(max = 20) List<@Size(max = 200) String> features,
                             @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal priceMonthly,
                             @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal priceQuarterly,
                             @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal priceSemiAnnual,
                             @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal priceYearly,
                             @Min(1) @Max(3660) Integer customDays,
                             @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal customPrice,
                             @Min(0) Integer maxEmployees,
                             @Min(0) Integer maxCustomers,
                             Boolean recommended,
                             String primaryColor, String secondaryColor, String backgroundColor,
                             String textColor, String buttonColor) {

        boolean legacy() {
            return priceMonthly == null && priceQuarterly == null && priceSemiAnnual == null && priceYearly == null
                    && customDays == null && customPrice == null && price != null && durationMonths != null;
        }

        PlanInput toInput() {
            return new PlanInput(name, description, features, priceMonthly, priceQuarterly, priceSemiAnnual,
                    priceYearly, customDays, customPrice, maxEmployees, maxCustomers, active, recommended,
                    primaryColor, secondaryColor, backgroundColor, textColor, buttonColor);
        }
    }

    public record PlanOrder(@NotNull @Size(min = 1, max = 200) List<@NotNull UUID> ids) {}

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ExtendRequest(@Min(1) @Max(366) int days) {}

    public record ActivateRequest(@NotBlank String planCode, String period) {}

    private final AdminService admin;
    private final SubscriptionService subscriptions;
    private final PlanService plans;

    public AdminController(AdminService admin, SubscriptionService subscriptions, PlanService plans) {
        this.admin = admin;
        this.subscriptions = subscriptions;
        this.plans = plans;
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
        return subscriptions.adminActivate(id, req.planCode(), req.period());
    }

    @GetMapping("/subscriptions")
    public List<SubscriptionView> subscriptions(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) UUID storeId) {
        return subscriptions.listByStatus(status, storeId);
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
    public List<PlanView> plans(@RequestParam(defaultValue = "false") boolean archived) {
        return plans.allPlans(archived);
    }

    @PostMapping("/plans")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public PlanView createPlan(@Valid @RequestBody PlanUpdate req) {
        return plans.create(req.toInput());
    }

    @PutMapping("/plans/order")
    public List<PlanView> reorderPlans(@Valid @RequestBody PlanOrder req) {
        return plans.reorder(req.ids());
    }

    @PutMapping("/plans/{id}")
    public PlanView updatePlan(@PathVariable UUID id, @Valid @RequestBody PlanUpdate req) {
        if (req.legacy()) {
            return plans.updateLegacy(id, req.name(), req.price(), req.durationMonths(),
                    req.active() == null || req.active());
        }
        return plans.update(id, req.toInput());
    }

    /** Deletes an unused package; one that stores already used is archived (hidden, history kept). */
    @DeleteMapping("/plans/{id}")
    public Map<String, String> deletePlan(@PathVariable UUID id) {
        return Map.of("result", plans.delete(id));
    }

    @PostMapping("/plans/{id}/restore")
    public PlanView restorePlan(@PathVariable UUID id) {
        return plans.restore(id);
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
