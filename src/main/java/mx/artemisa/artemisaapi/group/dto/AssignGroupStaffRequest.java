package mx.artemisa.artemisaapi.group.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignGroupStaffRequest(@NotNull UUID membershipId) {
}
