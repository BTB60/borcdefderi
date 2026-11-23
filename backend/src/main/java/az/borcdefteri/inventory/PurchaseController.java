package az.borcdefteri.inventory;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Mal qəbulu (purchase receipts from suppliers). */
@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {

    private final PurchaseService purchases;

    public PurchaseController(PurchaseService purchases) {
        this.purchases = purchases;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseService.Purchase create(@Valid @RequestBody PurchaseService.PurchaseRequest req) {
        return purchases.create(req);
    }

    @GetMapping
    public List<PurchaseService.Purchase> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(defaultValue = "100") int limit) {
        return purchases.list(from, to, companyId, limit);
    }

    @PostMapping("/{id}/returns")
    public PurchaseService.Purchase returnItems(@PathVariable UUID id, @Valid @RequestBody(required = false) PurchaseService.ReturnRequest req) {
        return purchases.returnItems(id, req);
    }

    /** Tədarükçünü dəyiş: the receipt (and what is still owed for it) moves to the right supplier. */
    @PostMapping("/{id}/supplier")
    public PurchaseService.Purchase changeSupplier(@PathVariable UUID id, @Valid @RequestBody PurchaseService.SupplierChange req) {
        return purchases.changeSupplier(id, req);
    }

    @GetMapping("/{id}")
    public PurchaseService.Purchase get(@PathVariable UUID id) {
        return purchases.get(id);
    }
}
