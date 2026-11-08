package az.borcdefteri.settings;

import az.borcdefteri.settings.PlatformSettingsService.Contact;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsController {

    public record ContactUpdate(
            @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
            @Size(max = 60, message = "M10 hesabı 60 simvoldan uzun ola bilməz") String m10Account,
            @Size(max = 120) String m10Recipient,
            @Size(max = 500) String paymentNote) {}

    private final PlatformSettingsService settings;

    public SettingsController(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /** Admin contact + M10 payment details for subscription purchases (public: also shown before login). */
    @GetMapping("/api/public/contact")
    public Contact contact() {
        return settings.contact();
    }

    @PutMapping("/api/admin/settings/contact")
    public Contact updateContact(@Valid @RequestBody ContactUpdate req) {
        return settings.updateContact(req.phone(), req.m10Account(), req.m10Recipient(), req.paymentNote());
    }
}
