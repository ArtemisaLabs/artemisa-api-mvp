package mx.artemisa.artemisaapi.identity;

public class TenantOnboardingConflictException extends RuntimeException {

    public TenantOnboardingConflictException(String message) {
        super(message);
    }
}
