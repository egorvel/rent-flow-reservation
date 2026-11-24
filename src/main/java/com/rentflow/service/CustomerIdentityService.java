package com.rentflow.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import com.rentflow.model.CustomerIdentity;

@Service
public class CustomerIdentityService {
    public CustomerIdentity currentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new AccessDeniedException("Operation not permitted");
        }
        return new CustomerIdentity(
                token.getToken().getIssuer().toString(), token.getToken().getSubject());
    }
}
