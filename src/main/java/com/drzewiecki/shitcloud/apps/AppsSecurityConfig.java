package com.drzewiecki.shitcloud.apps;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security for the apps port, checked before the API's chain. App documents are public by slug (accepted
 * on {@code test}) and nothing on this port takes credentials, so there is nothing to authenticate.
 * Framing must be allowed: the default {@code X-Frame-Options: DENY} would block every app. Who may frame
 * an app is decided by the document's CSP {@code frame-ancestors} instead. Cache headers are left to the
 * handlers: documents are {@code no-store}, the library mirror is cached.
 */
@Configuration
public class AppsSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain appsSecurityFilterChain(HttpSecurity http, AppOrigin appOrigin) throws Exception {
        http
                .securityMatcher(appOrigin::isAppsPort)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.disable())
                        .cacheControl(cache -> cache.disable()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
