package az.borcdefteri.customers;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PartyRequest(
        @NotBlank @Size(max = 150) String name,
        @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
        @Size(max = 1000) String note,
        /** Customers only: standing discount in %, applied at the till. Null on update = unchanged. */
        @DecimalMin(value = "0", message = "Endirim 0-100% arası olmalıdır")
        @DecimalMax(value = "100", message = "Endirim 0-100% arası olmalıdır") BigDecimal discountPercent
) {
    public PartyRequest(String name, String phone, String note) {
        this(name, phone, note, null);
    }
}
