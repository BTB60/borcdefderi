package az.borcdefteri.auth;

import az.borcdefteri.tenant.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TenantService tenant;

    public AuthController(AuthService authService, TenantService tenant) {
        this.authService = authService;
        this.tenant = tenant;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    public record VerifyRequest(@jakarta.validation.constraints.NotNull java.util.UUID challengeId,
                                @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 12) String code) {}

    /** Second step of two-step login: the 6-digit code from the e-mail. */
    @PostMapping("/login/verify")
    public AuthResponse verify(@Valid @RequestBody VerifyRequest request) {
        return authService.verifyLoginCode(request.challengeId(), request.code());
    }

    @GetMapping("/me")
    public MeResponse me() {
        return authService.me(tenant.currentUserId());
    }
}
