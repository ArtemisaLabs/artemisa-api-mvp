package mx.artemisa.artemisaapi.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "membership_role_assignments")
public class MembershipRoleAssignment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "tenant_membership_id", nullable = false)
    private UUID tenantMembershipId;

    @Column(name = "role_code", nullable = false, length = 40)
    private String roleCode;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MembershipRoleAssignment() {
        // Requerido por JPA
    }

    public MembershipRoleAssignment(
            UUID tenantId,
            UUID tenantMembershipId,
            String roleCode,
            UUID branchId
    ) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(
                tenantId,
                "tenantId no puede ser null"
        );
        this.tenantMembershipId = Objects.requireNonNull(
                tenantMembershipId,
                "tenantMembershipId no puede ser null"
        );
        this.roleCode = normalizeRoleCode(roleCode);
        this.branchId = branchId;
    }

    public static MembershipRoleAssignment create(
            UUID tenantId,
            UUID tenantMembershipId,
            String roleCode,
            UUID branchId
    ) {
        return new MembershipRoleAssignment(
                tenantId,
                tenantMembershipId,
                roleCode,
                branchId
        );
    }

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private static String normalizeRoleCode(String roleCode) {
        Objects.requireNonNull(roleCode, "roleCode no puede ser null");

        String normalizedRoleCode = roleCode
                .strip()
                .toUpperCase(Locale.ROOT);

        if (normalizedRoleCode.isEmpty()) {
            throw new IllegalArgumentException(
                    "roleCode no puede estar vacío"
            );
        }

        return normalizedRoleCode;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getTenantMembershipId() {
        return tenantMembershipId;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public UUID getBranchId() {
        return branchId;
    }
}