package mx.artemisa.artemisaapi.auth;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthenticationService {

    private static final long TOKEN_LIFETIME_SECONDS = 900;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    public AuthenticationService(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
    }

    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String tenantSlug = request.tenantSlug().trim().toLowerCase(Locale.ROOT);

        List<Account> accounts = jdbcTemplate.query(
                """
                SELECT
                    u.id AS user_id,
                    u.password_hash,
                    u.status AS user_status,
                    t.id AS tenant_id,
                    t.status AS tenant_status,
                    tm.id AS membership_id,
                    tm.status AS membership_status
                FROM app_users u
                JOIN tenant_memberships tm ON tm.app_user_id = u.id
                JOIN tenants t ON t.id = tm.tenant_id
                WHERE LOWER(u.email) = ?
                  AND LOWER(t.slug) = ?
                """,
                (resultSet, rowNumber) -> new Account(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("password_hash"),
                        resultSet.getString("user_status"),
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getString("tenant_status"),
                        resultSet.getObject("membership_id", UUID.class),
                        resultSet.getString("membership_status")),
                email,
                tenantSlug);

        Account account = accounts.stream()
                .findFirst()
                .orElseThrow(AuthenticationService::invalidCredentials);

        boolean accountIsActive =
                "ACTIVE".equals(account.userStatus())
                        && "ACTIVE".equals(account.tenantStatus())
                        && "ACTIVE".equals(account.membershipStatus());

        if (!accountIsActive
                || !passwordEncoder.matches(request.password(), account.passwordHash())) {
            throw invalidCredentials();
        }

        List<RoleAssignment> assignments = jdbcTemplate.query(
                """
                SELECT role_code, branch_id
                FROM membership_role_assignments
                WHERE tenant_membership_id = ?
                """,
                (resultSet, rowNumber) -> new RoleAssignment(
                        resultSet.getString("role_code"),
                        resultSet.getObject("branch_id", UUID.class)),
                account.membershipId());

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(TOKEN_LIFETIME_SECONDS);

        List<String> roles = assignments.stream()
                .map(RoleAssignment::roleCode)
                .distinct()
                .toList();

        List<String> branchIds = assignments.stream()
                .map(RoleAssignment::branchId)
                .filter(branchId -> branchId != null)
                .map(UUID::toString)
                .distinct()
                .toList();

        List<String> groupIds = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT a.class_group_id
                FROM class_group_staff_assignments a
                JOIN membership_role_assignments mra
                  ON mra.tenant_id = a.tenant_id
                 AND mra.tenant_membership_id = a.tenant_membership_id
                 AND mra.branch_id = a.branch_id
                WHERE a.tenant_id = ?
                  AND a.tenant_membership_id = ?
                  AND mra.role_code IN ('TEACHER', 'ASSISTANT')
                ORDER BY a.class_group_id
                """,
                UUID.class,
                account.tenantId(),
                account.membershipId())
                .stream()
                .map(UUID::toString)
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("artemisa-api")
                .subject(account.userId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("tenant_id", account.tenantId().toString())
                .claim("membership_id", account.membershipId().toString())
                .claim("roles", roles)
                .claim("branch_ids", branchIds)
                .claim("group_ids", groupIds)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();

        return new LoginResponse(token, "Bearer", TOKEN_LIFETIME_SECONDS);
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Credenciales inválidas.");
    }

    private record Account(
            UUID userId,
            String passwordHash,
            String userStatus,
            UUID tenantId,
            String tenantStatus,
            UUID membershipId,
            String membershipStatus) {
    }

    private record RoleAssignment(
            String roleCode,
            UUID branchId) {
    }
}
