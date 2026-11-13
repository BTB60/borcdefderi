package az.borcdefteri.payments;

import az.borcdefteri.payments.PaymentService.PaymentView;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/debts/{debtId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentView record(@PathVariable UUID debtId, @Valid @RequestBody PaymentRequest req) {
        return payments.record(debtId, req);
    }

    /** Shareable receipt link (for WhatsApp). Store-scoped: other stores' payments are not found. */
    @GetMapping("/payments/{id}/share-link")
    public java.util.Map<String, String> shareLink(@PathVariable UUID id) {
        return java.util.Map.of("url", payments.shareLink(id));
    }

    @GetMapping("/payments")
    public List<PaymentView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PaymentMethod method,
            @RequestParam(required = false) UUID debtId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean includeVoided) {
        return payments.list(from, to, method, debtId, limit, includeVoided);
    }

    /** Store owner only: corrects amount, method, date, description or note (same body as recording a payment). */
    @PutMapping("/payments/{id}")
    public PaymentView update(@PathVariable UUID id, @Valid @RequestBody PaymentRequest req) {
        return payments.update(id, req);
    }

    public record CancelRequest(@jakarta.validation.constraints.Size(max = 500) String reason) {}

    /** Store owner only: cancels (ləğv) the payment; it is kept, marked as cancelled, and booked back. */
    @PostMapping("/payments/{id}/cancel")
    public PaymentView cancel(@PathVariable UUID id, @RequestBody(required = false) @Valid CancelRequest req) {
        return payments.cancel(id, req == null ? null : req.reason());
    }
}
