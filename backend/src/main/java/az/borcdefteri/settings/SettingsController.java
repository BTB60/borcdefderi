package az.borcdefteri.settings;

import az.borcdefteri.settings.PlatformSettingsService.Contact;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsController {

    public record ContactUpdate(
            @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone) {}

    private final PlatformSettingsService settings;

    public SettingsController(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /** Admin contact for subscription purchases (public: also shown before login). */
    @GetMapping("/api/public/contact")
    public Contact contact() {
        return settings.contact();
    }

    @PutMapping("/api/admin/settings/contact")
    public Contact updateContact(@Valid @RequestBody ContactUpdate req) {
        return settings.updateContact(req.phone());
    }
}
