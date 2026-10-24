package mx.artemisa.artemisaapi.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}