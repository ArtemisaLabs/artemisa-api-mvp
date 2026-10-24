package mx.artemisa.artemisaapi.identity;

import mx.artemisa.artemisaapi.branch.Branch;
import mx.artemisa.artemisaapi.branch.BranchRepository;
import mx.artemisa.artemisaapi.identity.api.TenantOnboardingRequest;
import mx.artemisa.artemisaapi.identity.api.TenantOnboardingResponse;
import mx.artemisa.artemisaapi.tenant.Tenant;
import mx.artemisa.artemisaapi.tenant.TenantRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class TenantOnboardingService {

    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";
    private static final Pattern VALID_SLUG =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final AppUserRepository appUserRepository;
    private final TenantMembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final MembershipRoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantOnboardingService(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            AppUserRepository appUserRepository,
            TenantMembershipRepository membershipRepository,
            RoleRepository roleRepository,
            MembershipRoleAssignmentRepository roleAssignmentRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.appUserRepository = appUserRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public TenantOnboardingResponse onboard(TenantOnboardingRequest request) {
        String tenantName = requiredText(request.tenantName(), "tenantName");
        String slug = normalizeSlug(request.tenantSlug());
        String branchName = requiredText(request.branchName(), "branchName");
        String timezone = normalizeTimezone(request.timezone());
        String adminFullName = requiredText(
                request.adminFullName(),
                "adminFullName"
        );
        String adminEmail = normalizeEmail(request.adminEmail());
        String rawPassword = validatePassword(request.password());

        if (tenantRepository.existsBySlug(slug)) {
            throw new TenantOnboardingConflictException(
                    "El identificador del tenant ya está registrado."
            );
        }

        if (appUserRepository.existsByEmailIgnoreCase(adminEmail)) {
            throw new TenantOnboardingConflictException(
                    "El correo ya está registrado."
            );
        }

        if (!roleRepository.existsById(TENANT_ADMIN_ROLE)) {
            throw new IllegalStateException(
                    "No existe el rol de sistema TENANT_ADMIN."
            );
        }

        Tenant tenant = tenantRepository.save(
                Tenant.create(tenantName, slug)
        );

        Branch branch = branchRepository.save(
                Branch.create(tenant, branchName, timezone)
        );

        AppUser user = appUserRepository.save(
                AppUser.create(
                        adminEmail,
                        adminFullName,
                        passwordEncoder.encode(rawPassword)
                )
        );

        TenantMembership membership = membershipRepository.save(
                TenantMembership.create(tenant.getId(), user.getId())
        );

        roleAssignmentRepository.save(
                MembershipRoleAssignment.create(
                        tenant.getId(),
                        membership.getId(),
                        TENANT_ADMIN_ROLE,
                        null
                )
        );

        return new TenantOnboardingResponse(
                tenant.getId(),
                branch.getId(),
                user.getId(),
                membership.getId(),
                adminEmail
        );
    }

    private String normalizeSlug(String value) {
        String slug = requiredText(value, "tenantSlug")
                .toLowerCase(Locale.ROOT);

        if (!VALID_SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "tenantSlug solo puede contener letras, números y guiones."
            );
        }

        return slug;
    }

    private String normalizeEmail(String value) {
        return requiredText(value, "adminEmail")
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeTimezone(String value) {
        String timezone = requiredText(value, "timezone");

        try {
            return ZoneId.of(timezone).getId();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "La zona horaria no es válida.",
                    exception
            );
        }
    }

    private String validatePassword(String value) {
        String password = requiredText(value, "password");
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;

        if (password.length() < 12 || passwordBytes > 72) {
            throw new IllegalArgumentException(
                    "La contraseña debe tener al menos 12 caracteres "
                            + "y no superar 72 bytes en UTF-8."
            );
        }

        return password;
    }

    private String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " es obligatorio."
            );
        }

        return value.strip();
    }
}