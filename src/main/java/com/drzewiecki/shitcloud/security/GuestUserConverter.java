package com.drzewiecki.shitcloud.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Guests hold no role: they can open and use apps, nothing more. Their name is self-declared, so it is a
 * label for {@code updatedBy}, not proof of who they are.
 */
@Component
public class GuestUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String name = jwt.getClaimAsString(GuestTokenService.NAME_CLAIM);
        CurrentUser user = new CurrentUser(jwt.getSubject(), name, List.of(), false, false, true);
        return new UserAuthentication(jwt, user, List.of());
    }
}
