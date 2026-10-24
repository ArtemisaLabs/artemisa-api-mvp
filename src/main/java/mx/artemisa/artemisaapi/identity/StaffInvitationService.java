package mx.artemisa.artemisaapi.identity;

import mx.artemisa.artemisaapi.audit.AuditService;
import mx.artemisa.artemisaapi.identity.api.AcceptStaffInvitationRequest;
import mx.artemisa.artemisaapi.identity.api.AcceptStaffInvitationResponse;
import mx.artemisa.artemisaapi.identity.api.CreateStaffInvitationRequest;
import mx.artemisa.artemisaapi.identity.api.StaffInvitationResponse;
import mx.artemisa.artemisaapi.security.BranchAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class StaffInvitationService {

    private static final Set<String> INVITABLE_ROLES =
            Set.of("BRANCH_DIRECTOR", "TEACHER", "ASSISTANT");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final BranchAuthorizationService authorizationService;
    private final AuditService auditService;

    public StaffInvitationService(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            BranchAuthorizationService authorizationService,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional
    public StaffInvitationResponse create(
            UUID branchId,
            CreateStaffInvitationRequest request,
            Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        UUID invitedBy = authorizationService.requireMembershipId(jwt);
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        String fullName = request.fullName().strip();
        String roleCode = request.roleCode().strip().toUpperCase(Locale.ROOT);

        if (!INVITABLE_ROLES.contains(roleCode)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El rol solicitado no se puede asignar mediante invitación.");
        }

        Boolean membershipExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM app_users u
                    JOIN tenant_memberships tm ON tm.app_user_id = u.id
                    WHERE lower(u.email) = ? AND tm.tenant_id = ?
                )
                """, Boolean.class, email, tenantId);
        if (Boolean.TRUE.equals(membershipExists)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ese usuario ya pertenece a esta institución.");
        }

        Boolean pendingInviteExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM staff_invitations
                    WHERE tenant_id = ? AND branch_id = ? AND lower(email) = ?
                      AND accepted_at IS NULL AND expires_at > now()
                )
                """, Boolean.class, tenantId, branchId, email);
        if (Boolean.TRUE.equals(pendingInviteExists)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe una invitación vigente para ese correo en la sucursal.");
        }

        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenHash = sha256(token);
        UUID invitationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(48, ChronoUnit.HOURS);

        jdbcTemplate.update("""
                INSERT INTO staff_invitations (
                    id, tenant_id, branch_id, email, full_name, role_code,
                    token_hash, invited_by_membership_id, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                invitationId, tenantId, branchId, email, fullName, roleCode,
                tokenHash, invitedBy, expiresAt.atOffset(java.time.ZoneOffset.UTC));

        auditService.record(tenantId, branchId, invitedBy,
                "STAFF_INVITATION_CREATED", "STAFF_INVITATION", invitationId);

        return new StaffInvitationResponse(
                invitationId, email, roleCode, expiresAt, token);
    }

    @Transactional
    public AcceptStaffInvitationResponse accept(AcceptStaffInvitationRequest request) {
        String tokenHash = sha256(request.token().strip());
        Invitation invitation = jdbcTemplate.query("""
                SELECT id, tenant_id, branch_id, email, full_name, role_code,
                       accepted_at, expires_at
                FROM staff_invitations
                WHERE token_hash = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next() ? new Invitation(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getObject("branch_id", UUID.class),
                        resultSet.getString("email"),
                        resultSet.getString("full_name"),
                        resultSet.getString("role_code"),
                        resultSet.getObject("accepted_at", java.time.OffsetDateTime.class),
                        resultSet.getObject("expires_at", java.time.OffsetDateTime.class)
                ) : null,
                tokenHash);

        if (invitation == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "La invitación no existe o el token no es válido.");
        }
        if (invitation.acceptedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La invitación ya fue aceptada.");
        }
        if (!invitation.expiresAt().toInstant().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "La invitación expiró; solicita una nueva.");
        }

        UUID userId = findOrCreateUser(invitation, request.password());
        Boolean membershipExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM tenant_memberships
                    WHERE tenant_id = ? AND app_user_id = ?
                )
                """, Boolean.class, invitation.tenantId(), userId);
        if (Boolean.TRUE.equals(membershipExists)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El usuario ya pertenece a esta institución.");
        }

        UUID membershipId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO tenant_memberships (
                    id, tenant_id, app_user_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', now(), now())
                """, membershipId, invitation.tenantId(), userId);

        jdbcTemplate.update("""
                INSERT INTO membership_role_assignments (
                    id, tenant_id, tenant_membership_id, role_code, branch_id
                ) VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), invitation.tenantId(), membershipId,
                invitation.roleCode(), invitation.branchId());

        jdbcTemplate.update("""
                UPDATE staff_invitations SET accepted_at = now()
                WHERE id = ? AND accepted_at IS NULL
                """, invitation.id());

        auditService.record(invitation.tenantId(), invitation.branchId(), membershipId,
                "STAFF_INVITATION_ACCEPTED", "STAFF_MEMBERSHIP", membershipId);

        return new AcceptStaffInvitationResponse(
                userId, membershipId, invitation.tenantId(), invitation.branchId(),
                invitation.roleCode());
    }

    private UUID findOrCreateUser(Invitation invitation, String rawPassword) {
        var existing = jdbcTemplate.query("""
                SELECT id, status FROM app_users WHERE lower(email) = ?
                """,
                resultSet -> resultSet.next()
                        ? new ExistingUser(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("status"))
                        : null,
                invitation.email());

        if (existing != null) {
            if (!"ACTIVE".equals(existing.status())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La cuenta asociada está deshabilitada.");
            }
            return existing.id();
        }

        if (rawPassword == null || rawPassword.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Define una contraseña de al menos 12 caracteres.");
        }
        String password = rawPassword;
        if (password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos 12 caracteres y no superar 72 bytes en UTF-8.");
        }

        UUID userId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_users (
                        id, email, full_name, password_hash, status,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                    """,
                    userId,
                    invitation.email(),
                    invitation.fullName(),
                    passwordEncoder.encode(password));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe una cuenta con ese correo.", exception);
        }
        return userId;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private record Invitation(
            UUID id,
            UUID tenantId,
            UUID branchId,
            String email,
            String fullName,
            String roleCode,
            java.time.OffsetDateTime acceptedAt,
            java.time.OffsetDateTime expiresAt) { }

    private record ExistingUser(UUID id, String status) { }
}
