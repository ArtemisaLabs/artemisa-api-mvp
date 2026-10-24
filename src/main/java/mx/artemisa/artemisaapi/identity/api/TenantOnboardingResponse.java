package mx.artemisa.artemisaapi.identity.api;

import java.util.UUID;

public record TenantOnboardingResponse(
        UUID tenantId,
        UUID branchId,
        UUID userId,
        UUID membershipId,
        String adminEmail
) {
}