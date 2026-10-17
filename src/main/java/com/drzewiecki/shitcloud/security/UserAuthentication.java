package com.drzewiecki.shitcloud.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

public class UserAuthentication extends AbstractAuthenticationToken {

    private final Jwt jwt;
    private final CurrentUser user;

    public UserAuthentication(Jwt jwt, CurrentUser user, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.jwt = jwt;
        this.user = user;
        setAuthenticated(true);
    }

    public CurrentUser getUser() {
        return user;
    }

    @Override
    public Object getCredentials() {
        return jwt.getTokenValue();
    }

    @Override
    public Object getPrincipal() {
        return user.id();
    }
}
