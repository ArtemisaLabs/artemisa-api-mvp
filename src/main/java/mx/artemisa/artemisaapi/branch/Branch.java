package mx.artemisa.artemisaapi.branch;

import mx.artemisa.artemisaapi.tenant.Tenant;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "branches")
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 80)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BranchStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Branch() {
        // Requerido por JPA
    }

    private Branch(Tenant tenant, String name, String timezone) {
        this.tenant = Objects.requireNonNull(tenant);
        this.name = Objects.requireNonNull(name);
        this.timezone = ZoneId.of(timezone).getId();
        this.status = BranchStatus.ACTIVE;
    }

    public static Branch create(Tenant tenant, String name, String timezone) {
        return new Branch(tenant, name, timezone);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public String getName() {
        return name;
    }

    public String getTimezone() {
        return timezone;
    }

    public BranchStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
