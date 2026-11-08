package az.borcdefteri.subscriptions;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Store-facing subscription state.
 * status: ACTIVE, PENDING, EXPIRED, REJECTED or NONE (never subscribed).
 * expiresAt: the exact moment access ends (start of the day after endDate, business time zone) — drives the countdown.
 */
public record SubscriptionSummary(
        boolean active,
        String status,
        String planCode,
        String planName,
        LocalDate startDate,
        LocalDate endDate,
        long daysRemaining,
        boolean renewalReminder,
        OffsetDateTime expiresAt
) {}
