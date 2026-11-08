package az.borcdefteri.auth;

public record AuthResponse(String token, long expiresInSeconds, MeResponse me) {}
