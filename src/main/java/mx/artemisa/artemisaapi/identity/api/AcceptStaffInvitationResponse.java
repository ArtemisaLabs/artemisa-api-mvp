package mx.artemisa.artemisaapi.identity.api;

import java.util.UUID;

public record AcceptStaffInvitationResponse(
        UUID userId,
        UUID membershipId,
        UUID tenantId,
        UUID branchId,
        String roleCode) {
}
