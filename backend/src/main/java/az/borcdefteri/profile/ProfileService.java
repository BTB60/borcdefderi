package az.borcdefteri.profile;

import az.borcdefteri.audit.AuditService;
import az.borcdefteri.auth.AuthResponse;
import az.borcdefteri.auth.AuthService;
import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Passwords;
import az.borcdefteri.config.JwtService;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * "Parametrlər": the signed-in user's own profile — personal data, password, profile picture — and, for store
 * owners, the store's contact details. Works for SUPER_ADMIN, store owners and employees alike; it never needs an
 * active subscription (an expired store must still be able to fix its details or change its password).
 */
@Service
public class ProfileService {

    static final int AVATAR_MAX_BYTES = 3 * 1024 * 1024;
    static final int AVATAR_SIZE = 256;

    public record Profile(UUID userId, String email, String fullName, String phone, String platformRole,
                          String role, UUID storeId, String storeName, String storePhone, String storeAddress,
                          boolean hasAvatar, Long avatarVersion) {}

    public record Avatar(byte[] content, String contentType) {}

    private final JdbcClient jdbc;
    private final TenantService tenant;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final AuthService auth;
    private final AuditService audit;

    public ProfileService(JdbcClient jdbc, TenantService tenant, PasswordEncoder passwordEncoder, JwtService jwt,
                          AuthService auth, AuditService audit) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.auth = auth;
        this.audit = audit;
    }

    public Profile get() {
        UUID userId = tenant.currentUserId();
        return jdbc.sql("""
                SELECT u.id, u.email, u.full_name, u.phone, u.platform_role, u.avatar_updated_at,
                       m.role, s.id AS store_id, s.name AS store_name, s.phone AS store_phone, s.address AS store_address
                FROM users u
                LEFT JOIN store_members m ON m.user_id = u.id
                LEFT JOIN stores s ON s.id = m.store_id
                WHERE u.id = :id
                """).param("id", userId)
                .query((rs, n) -> {
                    var avatarAt = rs.getObject("avatar_updated_at", java.time.OffsetDateTime.class);
                    return new Profile(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
                            rs.getString("phone"), rs.getString("platform_role"), rs.getString("role"),
                            rs.getObject("store_id", UUID.class), rs.getString("store_name"),
                            rs.getString("store_phone"), rs.getString("store_address"),
                            avatarAt != null, avatarAt == null ? null : avatarAt.toInstant().toEpochMilli());
                })
                .optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Hesab tapılmadı"));
    }

    /** Name and phone; changing the e-mail (login) additionally requires the current password. */
    @Transactional
    public Profile update(String fullName, String phone, String email, String currentPassword) {
        UUID userId = tenant.currentUserId();
        Profile current = get();
        String newEmail = email == null || email.isBlank() ? current.email() : email.trim().toLowerCase(Locale.ROOT);
        boolean emailChanged = !newEmail.equals(current.email());
        if (emailChanged) {
            requireCurrentPassword(userId, currentPassword);
            boolean taken = jdbc.sql("SELECT COUNT(*) FROM users WHERE email = :e AND id <> :id")
                    .param("e", newEmail).param("id", userId).query(Long.class).single() > 0;
            if (taken) {
                throw ApiException.conflict("EMAIL_EXISTS", "Bu e-poçt ünvanı artıq qeydiyyatdadır");
            }
        }
        jdbc.sql("UPDATE users SET full_name = :name, phone = :phone, email = :email WHERE id = :id")
                .param("name", fullName.trim()).param("phone", blankToNull(phone)).param("email", newEmail)
                .param("id", userId).update();
        audit.log(current.storeId(), userId, "PROFILE_UPDATED", "USER", userId.toString(),
                emailChanged ? "Ad/telefon, e-poçt: " + current.email() + " -> " + newEmail : "Ad/telefon yeniləndi");
        return get();
    }

    /** Store name / phone / address — store owner only. Does not require an active subscription. */
    @Transactional
    public Profile updateStore(String name, String phone, String address) {
        CurrentMember m = tenant.requireMember();
        if (!m.isOwner()) {
            throw ApiException.forbidden("OWNER_ONLY", "Mağaza məlumatlarını yalnız mağaza sahibi dəyişə bilər");
        }
        jdbc.sql("UPDATE stores SET name = :name, phone = :phone, address = :address WHERE id = :id")
                .param("name", name.trim()).param("phone", blankToNull(phone)).param("address", blankToNull(address))
                .param("id", m.storeId()).update();
        audit.log(m.storeId(), m.userId(), "STORE_UPDATED", "STORE", m.storeId().toString(), "Mağaza: " + name.trim());
        return get();
    }

    /**
     * Changes the password and returns a fresh token for this device. Tokens issued before the change stop working
     * (the user is signed out on other devices).
     */
    @Transactional
    public AuthResponse changePassword(String currentPassword, String newPassword) {
        UUID userId = tenant.currentUserId();
        requireCurrentPassword(userId, currentPassword);
        Passwords.requireBcryptLength(newPassword);
        String platformRole = jdbc.sql("SELECT platform_role FROM users WHERE id = :id").param("id", userId)
                .query(String.class).single();
        int min = "SUPER_ADMIN".equals(platformRole) ? 12 : 8;
        if (newPassword.length() < min) {
            throw ApiException.badRequest("PASSWORD_TOO_SHORT", "Yeni şifrə ən azı " + min + " simvol olmalıdır");
        }
        if (newPassword.equals(currentPassword)) {
            throw ApiException.badRequest("PASSWORD_UNCHANGED", "Yeni şifrə cari şifrədən fərqli olmalıdır");
        }
        jdbc.sql("UPDATE users SET password_hash = :hash, password_changed_at = now() WHERE id = :id")
                .param("hash", passwordEncoder.encode(newPassword)).param("id", userId).update();
        UUID storeId = jdbc.sql("SELECT store_id FROM store_members WHERE user_id = :id").param("id", userId)
                .query(UUID.class).optional().orElse(null);
        audit.log(storeId, userId, "PASSWORD_CHANGED", "USER", userId.toString(), "Şifrə dəyişdirildi");
        return new AuthResponse(jwt.issue(userId, platformRole), jwt.expiresInSeconds(), auth.me(userId));
    }

    // ---------------------------------------------------------------- profile picture

    /**
     * PNG or JPEG up to 3 MB. The image is decoded, cropped to a centred square, scaled to 256×256 and re-encoded
     * as PNG — this removes EXIF/GPS metadata and anything that is not a real picture.
     */
    @Transactional
    public Profile uploadAvatar(MultipartFile file) {
        UUID userId = tenant.currentUserId();
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("AVATAR_REQUIRED", "Şəkil seçin");
        }
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readNBytes(AVATAR_MAX_BYTES + 1);
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_IMAGE", "Şəkil oxuna bilmədi");
        }
        if (bytes.length > AVATAR_MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Şəklin ölçüsü 3 MB-dan çox ola bilməz");
        }
        if (!isPng(bytes) && !isJpeg(bytes)) {
            throw ApiException.badRequest("INVALID_IMAGE", "Yalnız PNG və ya JPG şəkil yükləmək olar");
        }
        byte[] png = normalize(bytes);
        jdbc.sql("UPDATE users SET avatar = :img, avatar_type = 'image/png', avatar_updated_at = now() WHERE id = :id")
                .param("img", png).param("id", userId).update();
        return get();
    }

    @Transactional
    public Profile deleteAvatar() {
        UUID userId = tenant.currentUserId();
        jdbc.sql("UPDATE users SET avatar = NULL, avatar_type = NULL, avatar_updated_at = NULL WHERE id = :id")
                .param("id", userId).update();
        return get();
    }

    /** The caller's own picture (other users' pictures are never served). */
    public Avatar avatar() {
        UUID userId = tenant.currentUserId();
        return jdbc.sql("SELECT avatar, avatar_type FROM users WHERE id = :id AND avatar IS NOT NULL")
                .param("id", userId)
                .query((rs, n) -> new Avatar(rs.getBytes("avatar"), rs.getString("avatar_type")))
                .optional().orElseThrow(() -> ApiException.notFound("Profil şəkli yoxdur"));
    }

    static byte[] normalize(byte[] bytes) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            src = null;
        }
        if (src == null || src.getWidth() < 16 || src.getHeight() < 16 || (long) src.getWidth() * src.getHeight() > 40_000_000L) {
            throw ApiException.badRequest("INVALID_IMAGE", "Şəkil oxuna bilmədi və ya çox kiçikdir");
        }
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        int target = Math.min(AVATAR_SIZE, side);
        BufferedImage out = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, target, target, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ImageIO.write(out, "png", os);
            return os.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_FAILED", "Şəkil saxlanıla bilmədi");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void requireCurrentPassword(UUID userId, String currentPassword) {
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id").param("id", userId)
                .query(String.class).single();
        if (currentPassword == null || !Passwords.fitsBcrypt(currentPassword)
                || !passwordEncoder.matches(currentPassword, hash)) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Cari şifrə yanlışdır");
        }
    }

    private static boolean isPng(byte[] h) {
        return h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
    }

    private static boolean isJpeg(byte[] h) {
        return h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
