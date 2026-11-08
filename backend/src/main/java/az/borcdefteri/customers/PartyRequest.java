package az.borcdefteri.customers;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PartyRequest(
        @NotBlank @Size(max = 150) String name,
        @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
        @Size(max = 1000) String note
) {}
