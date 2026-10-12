package com.example.inventory.tenancy;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class TenantFilter extends OncePerRequestFilter {

    static final String TENANT_CLAIM = "tid";
    static final String ROLE_CLAIM = "role";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        bindFromVerifiedTokenOnly();
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static void bindFromVerifiedTokenOnly() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return;
        }

        String tenantClaim = jwt.getClaimAsString(TENANT_CLAIM);
        String subject = jwt.getSubject();
        if (tenantClaim == null || subject == null) {
            return;
        }

        TenantContext.bind(new TenantContext.TenantIdentity(
                UUID.fromString(tenantClaim),
                UUID.fromString(subject),
                jwt.getClaimAsString(ROLE_CLAIM)));
    }
}
