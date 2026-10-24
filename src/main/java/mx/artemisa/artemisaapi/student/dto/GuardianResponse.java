package mx.artemisa.artemisaapi.student.dto;

import java.util.UUID;

public record GuardianResponse(
        UUID id,
        String firstName,
        String lastName,
        String relationship,
        boolean primaryContact,
        boolean canPickUp
) {
}
