package az.borcdefteri.auth;

import az.borcdefteri.auth.PasswordResetService.ForgotResult;
import az.borcdefteri.auth.PasswordResetService.ResetRequest;
import az.borcdefteri.auth.PasswordResetService.TemporaryPassword;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Forgotten-password recovery: self-service by e-mail, or a temporary password from the admin / store owner. */
@RestController
public class PasswordResetController {

    public record ForgotRequest(@NotBlank @Size(max = 190) String email) {}

    public record ResetRequestBody(@NotBlank @Size(max = 200) String token,
                                   @NotBlank @Size(min = 8, max = 72, message = "Yeni şifrə ən azı 8 simvol olmalıdır") String newPassword) {}

    private final PasswordResetService resets;

    public PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    /** Public. Same response whether or not the e-mail exists. Rate-limited like login. */
    @PostMapping("/api/auth/forgot-password")
    public ForgotResult forgot(@Valid @RequestBody ForgotRequest req) {
        return resets.forgot(req.email());
    }

    /** Public. One-time link from the e-mail. */
    @PostMapping("/api/auth/reset-password")
    public Map<String, Boolean> reset(@Valid @RequestBody ResetRequestBody req) {
        resets.resetWithToken(req.token(), req.newPassword());
        return Map.of("ok", true);
    }

    @GetMapping("/api/admin/password-requests")
    public List<ResetRequest> openRequests() {
        return resets.openRequests();
    }

    @PostMapping("/api/admin/password-requests/{id}/dismiss")
    public Map<String, Boolean> dismiss(@PathVariable UUID id) {
        resets.dismissRequest(id);
        return Map.of("ok", true);
    }

    /** Platform admin issues a one-time temporary password (shown once; the user must change it after login). */
    @PostMapping("/api/admin/users/{id}/reset-password")
    public TemporaryPassword adminReset(@PathVariable UUID id) {
        return resets.adminReset(id);
    }

    /** Store owner issues a temporary password for an employee. */
    @PostMapping("/api/employees/{memberId}/reset-password")
    public TemporaryPassword ownerReset(@PathVariable UUID memberId) {
        return resets.ownerResetEmployee(memberId);
    }
}
