package mx.artemisa.artemisaapi.branch;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class BranchService {

    private final BranchReadRepository branchReadRepository;

    public BranchService(BranchReadRepository branchReadRepository) {
        this.branchReadRepository = branchReadRepository;
    }

    public List<BranchResponse> findVisibleBranches(
            UUID tenantId,
            UUID membershipId) {
        return branchReadRepository.findVisibleForMembership(tenantId, membershipId);
    }
}
