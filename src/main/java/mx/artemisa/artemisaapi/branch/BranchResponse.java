package mx.artemisa.artemisaapi.branch;

import java.util.UUID;

public record BranchResponse(
        UUID id,
        UUID tenantId,
        String name,
        String timezone,
        String status
) {
}
