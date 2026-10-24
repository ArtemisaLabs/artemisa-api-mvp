
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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {
        // Requerido por JPA
    }

    private AppUser(String email, String fullName, String passwordHash) {
        this.id = UUID.randomUUID();
        this.email = normalizeEmail(email);
        this.fullName = requireText(fullName, "fullName");
        this.passwordHash = requireText(passwordHash, "passwordHash");
        this.status = Status.ACTIVE;
    }

    public static AppUser create(
            String email,
            String fullName,
            String passwordHash
    ) {
        return new AppUser(email, fullName, passwordHash);
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

    private static String normalizeEmail(String email) {
        return requireText(email, "email").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " no puede ser null");

        String normalizedValue = value.strip();

        if (normalizedValue.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " no puede estar vacío"
            );
        }

        return normalizedValue;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public Status getStatus() {
        return status;
    }

    public enum Status {
        ACTIVE,
        DISABLED
    }
}