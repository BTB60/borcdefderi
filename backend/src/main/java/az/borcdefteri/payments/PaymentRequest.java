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
        @Size(max = 1000) String note
) {}
