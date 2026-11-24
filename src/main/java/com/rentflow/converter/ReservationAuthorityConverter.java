package com.rentflow.converter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

public class ReservationAuthorityConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final Set<String> PERMISSIONS = Set.of(
            "reservation:create",
            "reservation:read",
            "reservation:list",
            "reservation:cancel",
            "reservation:read:any",
            "reservation:list:any",
            "reservation:cancel:any",
            "reservation:manage");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(new JwtGrantedAuthoritiesConverter().convert(jwt));
        if (jwt.getClaim("resource_access") instanceof Map<?, ?> resources
                && resources.get("reservation-api") instanceof Map<?, ?> reservation
                && reservation.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String permission && PERMISSIONS.contains(permission)) {
                    authorities.add(new SimpleGrantedAuthority("PERM_" + permission));
                }
            }
        }
        return authorities;
    }
}
