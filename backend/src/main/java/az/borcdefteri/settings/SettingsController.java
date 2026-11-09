package az.borcdefteri.settings;

import az.borcdefteri.settings.PlatformSettingsService.Contact;
import az.borcdefteri.settings.PlatformSettingsService.ContactInput;
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
            @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "WhatsApp nömrəsi düzgün deyil") String phone,
            @Size(max = 60, message = "M10 hesabı 60 simvoldan uzun ola bilməz") String m10Account,
            @Size(max = 120) String m10Recipient,
            @Size(max = 500) String paymentNote,
            @Pattern(regexp = "^[0-9 -]{0,30}$", message = "Kart nömrəsi yalnız rəqəmlərdən ibarət olmalıdır") String cardNumber,
            @Size(max = 120) String cardHolder,
            @Size(max = 120) String cardBank,
            @Size(max = 500) String cardNote,
            @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Əlavə əlaqə nömrəsi düzgün deyil") String extraPhone,
            @Size(max = 300) String extraContact,
            @Size(max = 1000) String whatsappTemplate,
            @Size(max = 1000) String paymentInstructions,
            @Size(max = 1000) String afterPaymentText) {

        ContactInput toInput() {
            return new ContactInput(phone, m10Account, m10Recipient, paymentNote, cardNumber, cardHolder, cardBank,
                    cardNote, extraPhone, extraContact, whatsappTemplate, paymentInstructions, afterPaymentText);
        }
    }

    private final PlatformSettingsService settings;

    public SettingsController(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /** WhatsApp contact + M10 / bank card payment details for subscription purchases (public: also shown before login). */
    @GetMapping("/api/public/contact")
    public Contact contact() {
        Contact c = settings.contact();
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof az.borcdefteri.config.AuthenticatedUser) {
            return c;
        }
        // Before login only the contact numbers are needed: the bank card is shown to signed-in stores only.
        return new Contact(c.phone(), c.whatsapp(), c.m10Account(), c.m10Recipient(), c.paymentNote(),
                null, null, null, null, c.extraPhone(), c.extraContact(), c.whatsappTemplate(),
                c.paymentInstructions(), c.afterPaymentText());
    }

    @PutMapping("/api/admin/settings/contact")
    public Contact updateContact(@Valid @RequestBody ContactUpdate req) {
        return settings.updateContact(req.toInput());
    }
}
