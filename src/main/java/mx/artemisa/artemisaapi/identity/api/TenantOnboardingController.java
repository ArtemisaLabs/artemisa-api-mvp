package mx.artemisa.artemisaapi.identity.api;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.identity.TenantOnboardingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/onboarding")
public class TenantOnboardingController {

    private final TenantOnboardingService onboardingService;

    public TenantOnboardingController(
            TenantOnboardingService onboardingService
    ) {
        this.onboardingService = onboardingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantOnboardingResponse onboard(
            @Valid @RequestBody TenantOnboardingRequest request
    ) {
        return onboardingService.onboard(request);
    }
}