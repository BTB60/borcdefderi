package az.borcdefteri.payments;

import az.borcdefteri.common.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Shareable payment receipt links (çek) that the store can send to its customer over WhatsApp:
 * {@code /api/public/receipts/{paymentId}/{token}}. The token is an HMAC-SHA256 of the payment id with a key derived
 * from JWT_SECRET, so links cannot be guessed or forged for other payments, and nothing extra is stored.
 * Note: rotating JWT_SECRET invalidates previously shared links (the PDF itself stays available in the app).
 */
@Component
public class ReceiptLinks {

    private static final int TOKEN_CHARS = 32; // 192 bits of the HMAC, base64url

    private final SecretKeySpec key;

    public ReceiptLinks(AppProperties props) {
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] derived = derive.doFinal("borcdefteri:receipt-link:v1".getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "HmacSHA256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public String token(UUID paymentId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] sig = mac.doFinal(paymentId.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig).substring(0, TOKEN_CHARS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison. */
    public boolean isValid(UUID paymentId, String token) {
        if (token == null || token.length() != TOKEN_CHARS) {
            return false;
        }
        return MessageDigest.isEqual(token(paymentId).getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }

    /** Absolute URL based on the current request (honours X-Forwarded-Proto/Host behind Render's proxy). */
    public String url(UUID paymentId) {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/public/receipts/{id}/{token}")
                .buildAndExpand(paymentId, token(paymentId))
                .toUriString();
    }
}
