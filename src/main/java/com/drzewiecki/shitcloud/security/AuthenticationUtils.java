package com.drzewiecki.shitcloud.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthenticationUtils {

    private AuthenticationUtils() {
    }

    /**
     * Only valid behind an authenticated endpoint — both sign-in paths produce a {@link UserAuthentication}.
     */
    public static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof UserAuthentication user) {
            return user.getUser();
        }
        throw new IllegalStateException("No signed-in user on this request");
    }
}
