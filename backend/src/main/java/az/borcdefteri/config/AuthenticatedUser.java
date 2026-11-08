package az.borcdefteri.config;

import java.time.Instant;
import java.util.UUID;

/**
 * Principal placed in the security context from a verified JWT. Store membership is never taken from the token.
 * issuedAt is checked against users.password_changed_at, so changing the password logs out other sessions.
 */
public record AuthenticatedUser(UUID userId, String platformRole, Instant issuedAt) {}
