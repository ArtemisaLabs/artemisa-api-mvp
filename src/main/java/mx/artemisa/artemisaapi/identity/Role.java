package mx.artemisa.artemisaapi.identity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Scope scope;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Role() {
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public Scope getScope() { return scope; }

    public enum Scope {
        TENANT,
        BRANCH
    }
}
