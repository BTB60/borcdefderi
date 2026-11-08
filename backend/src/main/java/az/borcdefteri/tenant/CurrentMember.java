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
        Set<String> permissions
) {
    public boolean isOwner() {
        return TenantService.OWNER.equals(role);
    }

    public boolean has(String permission) {
        return isOwner() || permissions.contains(permission);
    }
}
