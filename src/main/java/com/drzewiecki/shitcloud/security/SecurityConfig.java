package com.drzewiecki.shitcloud.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationManagerResolver<HttpServletRequest> resolver)
            throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Railway's healthcheck carries no token.
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Spring forwards failed requests here; without this a 429 or 404 from a public
                        // endpoint would be rewritten into a 401 on the way out.
                        .requestMatchers("/error").permitAll()
                        // Guest sign-in is how a token is obtained, so it cannot require one.
                        .requestMatchers(HttpMethod.POST, "/api/auth/guest").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(resolver)
                        .authenticationEntryPoint(new LoggingBearerTokenEntryPoint()));

        return http.build();
    }

    /**
     * Two token issuers, one request path. The token's own {@code iss} picks which validator checks it;
     * the claim is only a routing hint here, the selected decoder still verifies the signature. A token
     * from any other issuer is rejected with 401.
     */
    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver(
            AuthProperties properties,
            IdpUserConverter idpUserConverter,
            GuestTokenService guestTokens,
            GuestUserConverter guestUserConverter) {
        Map<String, AuthenticationManager> managers = new HashMap<>();
        JwtDecoder idpDecoder = idpJwtDecoder(properties);
        managers.put(properties.issuerUri(), manager(idpDecoder, idpUserConverter));
        if (guestTokens.enabled()) {
            JwtDecoder guestDecoder = guestTokens.decoder();
            managers.put(GuestTokenService.ISSUER, manager(guestDecoder, guestUserConverter));
        }
        return new JwtIssuerAuthenticationManagerResolver(managers::get);
    }

    /**
     * The GUI is a separate service on its own origin, so every call it makes is cross-origin. A bearer
     * token is attached deliberately by the code that holds it, not sent ambiently like a cookie, so there
     * is no {@code allowCredentials}. Origins are listed rather than {@code *}: this list is the written
     * record of who is expected to call the API.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        // X-ShitCloud-App: the host page names the app on screen for the dependency log (DataController).
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-ShitCloud-App"));
        // The preflight answer is stable, so re-asking on every request buys nothing but latency.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static JwtDecoder idpJwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri()).build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(properties.issuerUri());
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, audienceValidator));
        return decoder;
    }

    private static AuthenticationManager manager(JwtDecoder decoder,
                                                 Converter<Jwt, AbstractAuthenticationToken> converter) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return provider::authenticate;
    }
}
