package az.borcdefteri.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String timezone,
        Jwt jwt,
        Cors cors,
        Bootstrap bootstrap,
        Upload upload,
        RateLimit rateLimit,
        Subscription subscription,
        PasswordReset passwordReset
) {
    public record Jwt(String secret, long expirationMinutes) {}

    public record Cors(String allowedOrigins) {}

    public record Bootstrap(String superAdminEmail, String superAdminPassword, String superAdminName) {}

    public record Upload(String dir, long maxBytes) {}

    public record RateLimit(int authRequestsPerMinute) {}

    public record Subscription(int reminderDays) {}

    public record PasswordReset(String webUrl, String mailFrom, int tokenMinutes) {}
}
