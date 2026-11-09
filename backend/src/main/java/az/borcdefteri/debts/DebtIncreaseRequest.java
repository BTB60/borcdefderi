package az.borcdefteri.debts;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * "Borc artır": the customer took more on credit. {@code version} is the debt version the client saw,
 * so a double tap or a stale screen cannot add the same amount twice.
 */
public record DebtIncreaseRequest(
        @NotNull @DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
        @Digits(integer = 12, fraction = 2, message = "Məbləğ formatı düzgün deyil") BigDecimal amount,
        LocalDate date,
        @Size(max = 60, message = "Çek nömrəsi ən çox 60 simvol ola bilər") String receiptNumber,
        @Size(max = 500, message = "Qeyd ən çox 500 simvol ola bilər") String note,
        @NotNull Long version
) {}
