package com.rentflow.service;

import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class ReservationAuthorizationPolicy {
    private static final Set<String> HUMAN_CLIENTS = Set.of("reservation-swagger", "rentflow-pkce-check");

    public boolean canRead() {
        return canRead(SecurityContextHolder.getContext().getAuthentication());
    }

    public boolean canList() {
        return canList(SecurityContextHolder.getContext().getAuthentication());
    }

    public boolean canManage() {
        return canManage(SecurityContextHolder.getContext().getAuthentication());
    }

    public boolean canRead(Authentication authentication) {
        return allowed(authentication, "reservation:read") || allowed(authentication, "reservation:read:any");
    }

    public boolean canList(Authentication authentication) {
        return allowed(authentication, "reservation:list") || allowed(authentication, "reservation:list:any");
    }

    public boolean canManage(Authentication authentication) {
        return allowed(authentication, "reservation:manage");
    }

    public boolean canCreate() {
        return canCreate(SecurityContextHolder.getContext().getAuthentication());
    }

    public boolean canCancel() {
        return canCancel(SecurityContextHolder.getContext().getAuthentication());
    }

    public boolean canCreate(Authentication authentication) {
        return allowed(authentication, "reservation:create");
    }

    public boolean canCancel(Authentication authentication) {
        return allowed(authentication, "reservation:cancel") || allowed(authentication, "reservation:cancel:any");
    }

    public boolean canReadAny() {
        return allowed(SecurityContextHolder.getContext().getAuthentication(), "reservation:read:any");
    }

    public boolean canListAny() {
        return allowed(SecurityContextHolder.getContext().getAuthentication(), "reservation:list:any");
    }

    public boolean canCancelAny() {
        return allowed(SecurityContextHolder.getContext().getAuthentication(), "reservation:cancel:any");
    }

    private boolean allowed(Authentication authentication, String permission) {
        return authentication instanceof JwtAuthenticationToken token
                && token.isAuthenticated()
                && HUMAN_CLIENTS.contains(token.getToken().getClaimAsString("azp"))
                && token.getAuthorities().stream()
                        .anyMatch(authority -> ("PERM_" + permission).equals(authority.getAuthority()));
    }
}
