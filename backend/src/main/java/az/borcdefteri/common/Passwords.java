package az.borcdefteri.common;

import java.nio.charset.StandardCharsets;

/** BCrypt only uses the first 72 BYTES; Azerbaijani letters (ə, ş, ğ …) take 2 bytes each in UTF-8. */
public final class Passwords {

    public static final int MAX_BYTES = 72;

    private Passwords() {}

    public static boolean fitsBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    /** 400 instead of a server error when a new password is too long for BCrypt. */
    public static void requireBcryptLength(String password) {
        if (!fitsBcrypt(password)) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "Şifrə çox uzundur (maksimum 72 bayt)");
        }
    }
}
