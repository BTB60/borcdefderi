package az.borcdefteri.config;

import java.util.UUID;

/** Principal placed in the security context from a verified JWT. Store membership is never taken from the token. */
public record AuthenticatedUser(UUID userId, String platformRole) {}
