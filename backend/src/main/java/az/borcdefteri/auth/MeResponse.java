package az.borcdefteri.auth;

import az.borcdefteri.subscriptions.SubscriptionSummary;

import java.util.List;
import java.util.UUID;

public record MeResponse(
        UUID userId,
        String email,
        String fullName,
        String platformRole,
        String role,
        UUID storeId,
        String storeName,
        List<String> permissions,
        SubscriptionSummary subscription
) {}
