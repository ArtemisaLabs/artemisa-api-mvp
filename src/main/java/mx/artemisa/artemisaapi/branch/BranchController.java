package mx.artemisa.artemisaapi.branch;

import mx.artemisa.artemisaapi.security.BranchAuthorizationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {

    private final BranchService branchService;
    private final BranchAuthorizationService authorizationService;

    public BranchController(
            BranchService branchService,
            BranchAuthorizationService authorizationService) {
        this.branchService = branchService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public List<BranchResponse> listBranches(
            @AuthenticationPrincipal Jwt jwt) {

        return branchService.findVisibleBranches(
                authorizationService.requireTenantId(jwt),
                authorizationService.requireMembershipId(jwt));
    }
}
