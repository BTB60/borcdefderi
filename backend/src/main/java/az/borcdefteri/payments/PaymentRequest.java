package az.borcdefteri.payments;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentRequest(
        @NotNull @DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
        @Digits(integer = 12, fraction = 2, message = "Məbləğ formatı düzgün deyil") BigDecimal amount,
        @NotNull PaymentMethod paymentMethod,
        OffsetDateTime paidAt,
        @Size(max = 255) String description,
        @Size(max = 1000) String note,
        /** Optional cash desk / bank account the money moves through; default: by payment method. */
        java.util.UUID cashAccountId
) {
    public PaymentRequest(BigDecimal amount, PaymentMethod paymentMethod, OffsetDateTime paidAt, String description, String note) {
        this(amount, paymentMethod, paidAt, description, note, null);
    }
}
