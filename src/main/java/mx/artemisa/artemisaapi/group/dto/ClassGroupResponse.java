package mx.artemisa.artemisaapi.group.dto;

import java.util.UUID;

public record ClassGroupResponse(
        UUID id,
        UUID branchId,
        String name,
        int capacity,
        Integer minAgeMonths,
        Integer maxAgeMonths,
        String status,
        long occupiedSeats) {
}
