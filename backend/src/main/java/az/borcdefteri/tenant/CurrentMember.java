package az.borcdefteri.tenant;

import java.util.Set;
import java.util.UUID;

/** The authenticated caller's resolved tenant context for one request. */
public record CurrentMember(
        UUID userId,
        UUID memberId,
        UUID storeId,
        String storeName,
        String role,
        String platformRole,
        Set<String> permissions,
        /** Paid modules the store's package does not include (closed even for the owner). */
        Set<String> lockedModules
) {
    public CurrentMember(UUID userId, UUID memberId, UUID storeId, String storeName, String role, String platformRole,
                         Set<String> permissions) {
        this(userId, memberId, storeId, storeName, role, platformRole, permissions, Set.of());
    }

    public boolean isOwner() {
        return TenantService.OWNER.equals(role);
    }

    public boolean has(String permission) {
        return !lockedModules.contains(permission) && (isOwner() || permissions.contains(permission));
    }
}
