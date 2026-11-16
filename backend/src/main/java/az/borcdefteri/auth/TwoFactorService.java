package az.borcdefteri.auth;

import az.borcdefteri.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * İki addımlı giriş: when switched on for a user, the password alone is not enough — a 6-digit code sent to their
 * e-mail must be entered too. A code lives 10 minutes and allows 5 tries.
 */
@Service
public class TwoFactorService {

    static final int MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordResetMailer mailer;

    public TwoFactorService(JdbcClient jdbc, PasswordResetMailer mailer) {
        this.jdbc = jdbc;
        this.mailer = mailer;
    }

    public boolean mailAvailable() {
        return mailer.enabled();
    }

    boolean required(UUID userId) {
        return jdbc.sql("SELECT two_factor_enabled FROM users WHERE id = :id").param("id", userId).query(Boolean.class).single();
    }

    /** Sends a code and stops the login with TWO_FACTOR_REQUIRED (fields: challengeId, email hint). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class) // the code is kept although login stops here
    void challenge(UUID userId, String email) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO login_challenges (id, user_id, code_hash, expires_at) VALUES (:id, :u, :h, :exp)""")
                .param("id", id).param("u", userId).param("h", hash(id, code))
                .param("exp", OffsetDateTime.now().plusMinutes(MINUTES)).update();
        String name = jdbc.sql("SELECT full_name FROM users WHERE id = :id").param("id", userId).query(String.class).single();
        if (!mailer.sendLoginCode(email, name, code, MINUTES)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CODE_NOT_SENT",
                    "Giriş kodu e-poçta göndərilmədi. Bir az sonra yenidən cəhd edin");
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "TWO_FACTOR_REQUIRED",
                "Giriş kodu e-poçtunuza göndərildi (" + hint(email) + ")", Map.of("challengeId", id.toString(), "email", hint(email)));
    }

    /** Checks the code; returns the user it belongs to. */
    @Transactional(noRollbackFor = ApiException.class)
    UUID verify(UUID challengeId, String code) {
        record Row(UUID user, String hash, OffsetDateTime expires, int attempts, OffsetDateTime used) {}
        Row r = jdbc.sql("SELECT user_id, code_hash, expires_at, attempts, used_at FROM login_challenges WHERE id = :id FOR UPDATE")
                .param("id", challengeId)
                .query((rs, n) -> new Row(rs.getObject("user_id", UUID.class), rs.getString("code_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getInt("attempts"), rs.getObject("used_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "CODE_INVALID", "Kod yanlışdır"));
        if (r.used() != null || r.expires().isBefore(OffsetDateTime.now()) || r.attempts() >= MAX_ATTEMPTS) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CODE_EXPIRED", "Kodun vaxtı keçib. Yenidən daxil olun");
        }
        String given = code == null ? "" : code.replaceAll("\\D", "");
        if (!MessageDigest.isEqual(r.hash().getBytes(StandardCharsets.US_ASCII), hash(challengeId, given).getBytes(StandardCharsets.US_ASCII))) {
            jdbc.sql("UPDATE login_challenges SET attempts = attempts + 1 WHERE id = :id").param("id", challengeId).update();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CODE_INVALID", "Kod yanlışdır");
        }
        jdbc.sql("UPDATE login_challenges SET used_at = now() WHERE id = :id").param("id", challengeId).update();
        return r.user();
    }

    private static String hash(UUID challengeId, String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hint(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}
