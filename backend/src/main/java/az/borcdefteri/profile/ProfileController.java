package az.borcdefteri.profile;

import az.borcdefteri.auth.AuthResponse;
import az.borcdefteri.profile.ProfileService.Avatar;
import az.borcdefteri.profile.ProfileService.Profile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** "Parametrlər" — the signed-in user's own profile (admins, owners and employees). */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    public record ProfileUpdate(
            @NotBlank @Size(max = 150) String fullName,
            @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
            @Email @Size(max = 190) String email,
            @Size(max = 72) String currentPassword) {}

    public record StoreUpdate(
            @NotBlank @Size(max = 150) String name,
            @Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
            @Size(max = 255) String address) {}

    public record PasswordChange(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 8, max = 72, message = "Yeni şifrə ən azı 8 simvol olmalıdır") String newPassword) {}

    private final ProfileService profile;

    public ProfileController(ProfileService profile) {
        this.profile = profile;
    }

    @GetMapping
    public Profile get() {
        return profile.get();
    }

    @PutMapping
    public Profile update(@Valid @RequestBody ProfileUpdate req) {
        return profile.update(req.fullName(), req.phone(), req.email(), req.currentPassword());
    }

    @PutMapping("/store")
    public Profile updateStore(@Valid @RequestBody StoreUpdate req) {
        return profile.updateStore(req.name(), req.phone(), req.address());
    }

    /** Returns a new token: other devices are signed out, this one continues. */
    @PutMapping("/password")
    public AuthResponse changePassword(@Valid @RequestBody PasswordChange req) {
        return profile.changePassword(req.currentPassword(), req.newPassword());
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Profile uploadAvatar(@RequestParam("file") MultipartFile file) {
        return profile.uploadAvatar(file);
    }

    @DeleteMapping("/avatar")
    public Profile deleteAvatar() {
        return profile.deleteAvatar();
    }

    @GetMapping("/avatar")
    public ResponseEntity<byte[]> avatar() {
        Avatar a = profile.avatar();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(a.content());
    }
}
