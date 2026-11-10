package az.borcdefteri.customers;

import az.borcdefteri.debts.DebtType;
import az.borcdefteri.payments.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Requests of the customer / company account: "Borc əlavə et" and "Borc ödə". */
public final class PartyAccountRequests {

    private PartyAccountRequests() {
    }

    /**
     * "Borc əlavə et". {@code debtType} is required for companies (receivable or payable) and ignored for customers.
     * {@code expectedRemaining} is the balance the screen showed: if it no longer matches (double tap, another
     * user), the request is refused instead of being applied twice.
     */
    public record Charge(
            DebtType debtType,
            @NotNull @DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
            @Digits(integer = 12, fraction = 2, message = "Məbləğ formatı düzgün deyil") BigDecimal amount,
            LocalDate date,
            @Size(max = 60, message = "Çek nömrəsi ən çox 60 simvol ola bilər") String receiptNumber,
            @Size(max = 255, message = "Qeyd ən çox 255 simvol ola bilər") String note,
            BigDecimal expectedRemaining,
            /** Optional "son ödəmə tarixi": after it the account shows as overdue (gecikmiş). */
            LocalDate dueDate
    ) {}

    /** "Borc ödə": paid against the party's total balance, oldest debt first. */
    public record Pay(
            DebtType debtType,
            @NotNull @DecimalMin(value = "0.01", message = "Məbləğ 0-dan böyük olmalıdır")
            @Digits(integer = 12, fraction = 2, message = "Məbləğ formatı düzgün deyil") BigDecimal amount,
            @NotNull PaymentMethod paymentMethod,
            OffsetDateTime paidAt,
            @Size(max = 255) String description,
            @Size(max = 1000) String note,
            BigDecimal expectedRemaining,
            java.util.UUID cashAccountId
    ) {}
}
