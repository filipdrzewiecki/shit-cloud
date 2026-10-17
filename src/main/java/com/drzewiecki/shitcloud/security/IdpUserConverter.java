package com.drzewiecki.shitcloud.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns an identity-provider access token into the caller. Claim names come from {@link AuthProperties}
 * because Auth0 needs namespaced custom claims while ADFS / Entra ID use plain ones.
 */
@Component
public class IdpUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(IdpUserConverter.class);

    private static final Set<String> STANDARD_CLAIMS = Set.of(
            "iss", "sub", "aud", "iat", "exp", "nbf", "jti", "azp", "scope", "gty");

    private final AuthProperties properties;

    public IdpUserConverter(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (log.isDebugEnabled()) {
            jwt.getClaims().entrySet().stream()
                    .filter(e -> !STANDARD_CLAIMS.contains(e.getKey()))
                    .forEach(e -> log.debug("[JWT] custom claim: {}={}", e.getKey(), e.getValue()));
        }

        String id = jwt.getClaimAsString(properties.idClaim());
        // The identity claim is added by a post-login Action, not by Auth0 itself. Without it every write
        // would be anonymous, so a token missing it is refused rather than accepted with a placeholder.
        if (id == null || id.isBlank()) {
            throw new InvalidBearerTokenException("Token carries no " + properties.idClaim() + " claim");
        }
        String name = jwt.getClaimAsString(properties.nameClaim());
        String displayName = name == null || name.isBlank() ? id : name;
        List<String> claimedGroups = jwt.getClaimAsStringList(properties.groupsClaim());
        List<String> groups = claimedGroups == null ? List.of() : List.copyOf(claimedGroups);

        boolean admin = groups.contains(properties.adminsGroup());
        // The admin owns the platform, so everything a builder can do, an admin can too.
        boolean builder = admin || groups.contains(properties.buildersGroup());

        CurrentUser user = new CurrentUser(id, displayName, groups, builder, admin, false);
        return new UserAuthentication(jwt, user, authorities(builder, admin));
    }

    private static List<GrantedAuthority> authorities(boolean builder, boolean admin) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (builder) {
            authorities.add(new SimpleGrantedAuthority(Roles.BUILDER));
        }
        if (admin) {
            authorities.add(new SimpleGrantedAuthority(Roles.ADMIN));
        }
        return authorities;
    }
}
