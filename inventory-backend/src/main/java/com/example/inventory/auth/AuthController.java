package com.example.inventory.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.LoginRequest;
import com.example.inventory.auth.AuthDtos.RefreshRequest;
import com.example.inventory.auth.AuthDtos.TenantChoices;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;
import com.example.inventory.auth.LoginService.LoginOutcome;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.AuthRateLimiter;
import com.example.inventory.web.UnauthorizedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final TenantRegistrationService registrationService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final AuthRateLimiter rateLimiter;

    public AuthController(TenantRegistrationService registrationService,
                          LoginService loginService,
                          RefreshTokenService refreshTokenService,
                          AuthRateLimiter rateLimiter) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register-tenant")
    ResponseEntity<AuthTokens> registerTenant(@Valid @RequestBody TenantRegistrationRequest request,
                                              HttpServletRequest http) {
        rateLimiter.checkRegistration(http);
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.register(request));
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.checkLogin(http);

        return switch (loginService.login(request)) {
            case LoginOutcome.Authenticated authenticated ->
                    ResponseEntity.ok(authenticated.tokens());
            case LoginOutcome.MultipleTenants multiple ->
                    ResponseEntity.status(HttpStatus.MULTIPLE_CHOICES)
                            .body(new TenantChoices(multiple.tenants()));
        };
    }

    @PostMapping("/refresh")
    ResponseEntity<AuthTokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(refreshTokenService.refresh(request.refreshToken(), null));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        var identity = TenantContext.current()
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));

        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            refreshTokenService.logout(request.refreshToken());
        } else {
            refreshTokenService.logoutEverySession(identity.tenantId(), identity.userId());
        }
        return ResponseEntity.noContent().build();
    }
}
