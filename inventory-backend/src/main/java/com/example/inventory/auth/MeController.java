package com.example.inventory.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.UnauthorizedException;

@RestController
public class MeController {

    private final CurrentUserService currentUserService;

    public MeController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    ResponseEntity<CurrentUser> me() {
        var identity = TenantContext.current()
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));

        return currentUserService.load(identity.tenantId(), identity.userId())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));
    }
}
