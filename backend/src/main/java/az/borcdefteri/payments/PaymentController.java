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
            @RequestParam(defaultValue = "100") int limit) {
        return payments.list(from, to, method, debtId, limit);
    }
}
