package az.borcdefteri.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Reads "Authorization: Bearer &lt;jwt&gt;" and populates the security context. Not a Spring bean on purpose.
 * A valid signature is not enough: the user must still exist and be enabled, and the token must have been issued
 * after the last password change (changing the password signs out every other device).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JdbcClient jdbc;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcClient jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    private record UserState(boolean enabled, OffsetDateTime passwordChangedAt) {}

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.parse(header.substring(7).trim()).filter(this::stillValid).ifPresent(user -> {
                String authority = "SUPER_ADMIN".equals(user.platformRole()) ? "ROLE_SUPER_ADMIN" : "ROLE_USER";
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority(authority)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }

    private boolean stillValid(AuthenticatedUser user) {
        UserState state = jdbc.sql("SELECT enabled, password_changed_at FROM users WHERE id = :id")
                .param("id", user.userId())
                .query((rs, n) -> new UserState(rs.getBoolean("enabled"), rs.getObject("password_changed_at", OffsetDateTime.class)))
                .optional().orElse(null);
        if (state == null || !state.enabled()) {
            return false;
        }
        // JWT "iat" has whole-second precision; the token issued together with the password change stays valid.
        return state.passwordChangedAt() == null
                || !user.issuedAt().isBefore(state.passwordChangedAt().toInstant().truncatedTo(ChronoUnit.SECONDS));
    }
}
