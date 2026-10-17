package com.drzewiecki.shitcloud.security;

/**
 * Authority names. The {@code ROLE_} prefix is Spring's convention: {@code hasRole('BUILDER')} looks for
 * {@code ROLE_BUILDER}.
 */
public final class Roles {

    public static final String BUILDER = "ROLE_BUILDER";
    public static final String ADMIN = "ROLE_ADMIN";

    private Roles() {
    }
}
