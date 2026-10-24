package mx.artemisa.artemisaapi.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tenant_memberships")
public class TenantMembership {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "app_user_id", nullable = false)
    private UUID appUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.INVITED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantMembership() {
        // Requerido por JPA
    }

    /**
     * Crea una membresía pendiente de aceptación.
     */
    public TenantMembership(UUID tenantId, UUID appUserId) {
        this(tenantId, appUserId, Status.INVITED);
    }

    private TenantMembership(
            UUID tenantId,
            UUID appUserId,
            Status status
    ) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId no puede ser null");
        this.appUserId = Objects.requireNonNull(appUserId, "appUserId no puede ser null");
        this.status = Objects.requireNonNull(status, "status no puede ser null");
    }

    /**
     * Crea una membresía activa, por ejemplo, para el administrador
     * que acaba de registrar un tenant.
     */
    public static TenantMembership create(UUID tenantId, UUID appUserId) {
        return new TenantMembership(tenantId, appUserId, Status.ACTIVE);
    }

    @PrePersist
    void beforeInsert() {
        Instant now = Instant.now();

        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getAppUserId() {
        return appUserId;
    }

    public Status getStatus() {
        return status;
    }

    public enum Status {
        INVITED,
        ACTIVE,
        SUSPENDED,
        REVOKED
    }
}
