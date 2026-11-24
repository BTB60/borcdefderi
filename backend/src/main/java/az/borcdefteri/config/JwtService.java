package az.borcdefteri.config;

import az.borcdefteri.common.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private static final String CLAIM_PLATFORM_ROLE = "pr";
    /** Token kind: "cust" = a HesabPro Kart customer (never a store user). */
    private static final String CLAIM_TYPE = "typ";
    private static final String CUSTOMER = "cust";
    private static final Duration CUSTOMER_TTL = Duration.ofDays(90);

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        String secret = props.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET ortam dəyişəni ən azı 32 simvol olmalıdır");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(props.jwt().expirationMinutes());
    }

    public String issue(UUID userId, String platformRole) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_PLATFORM_ROLE, platformRole)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (CUSTOMER.equals(claims.get(CLAIM_TYPE, String.class))) return Optional.empty(); // a customer is no user
            return Optional.of(new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    claims.get(CLAIM_PLATFORM_ROLE, String.class),
                    claims.getIssuedAt() == null ? Instant.EPOCH : claims.getIssuedAt().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** HesabPro Kart: a customer's token, bound to their phone (994XXXXXXXXX). */
    public String issueCustomer(UUID accountId, String phone) {
        Instant now = Instant.now();
        return Jwts.builder().subject(accountId.toString()).claim(CLAIM_TYPE, CUSTOMER).claim("ph", phone)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(CUSTOMER_TTL))).signWith(key).compact();
    }

    /** The customer's account id and phone, only for a customer token. */
    public Optional<Map.Entry<UUID, String>> parseCustomer(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!CUSTOMER.equals(claims.get(CLAIM_TYPE, String.class))) return Optional.empty();
            return Optional.of(Map.entry(UUID.fromString(claims.getSubject()), claims.get("ph", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long expiresInSeconds() {
        return ttl.toSeconds();
    }
}
