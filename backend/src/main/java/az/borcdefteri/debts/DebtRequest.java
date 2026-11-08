package az.borcdefteri.debts;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DebtRequest(
        @NotNull DebtType debtType,
        @NotNull UUID partyId,
        @NotNull @DecimalMin(value = "0.01", message = "Borc məbləği 0-dan böyük olmalıdır")
        @Digits(integer = 12, fraction = 2, message = "Məbləğ formatı düzgün deyil") BigDecimal amount,
        @NotNull LocalDate debtDate,
        LocalDate dueDate,
        @Size(max = 255) String description,
        @Size(max = 1000) String note
) {}
