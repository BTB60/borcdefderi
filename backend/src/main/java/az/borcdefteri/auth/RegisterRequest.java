package az.borcdefteri.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 190) String email,
        @NotBlank @Size(min = 8, max = 72, message = "Şifrə ən azı 8 simvol olmalıdır") String password,
        @NotBlank @Size(max = 150) String fullName,
        @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
        @NotBlank @Size(max = 150) String storeName,
        @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String storePhone,
        @Size(max = 255) String storeAddress
) {}
