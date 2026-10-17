package com.drzewiecki.shitcloud.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables the {@code @PreAuthorize} rules every controller declares. Kept apart from {@link SecurityConfig}
 * so that authorization rules stay active even in a test setup that swaps out authentication.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
