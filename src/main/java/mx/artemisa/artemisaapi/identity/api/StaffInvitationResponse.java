package mx.artemisa.artemisaapi.identity.api;

import java.time.Instant;
import java.util.UUID;

public record StaffInvitationResponse(
        UUID invitationId,
        String email,
        String roleCode,
        Instant expiresAt,
        String invitationToken) {
}
