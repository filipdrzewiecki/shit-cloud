package com.drzewiecki.shitcloud.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Spring's standard 401, plus one log line saying why a token was refused. The reason otherwise travels
 * only in the {@code WWW-Authenticate} header to the browser, and "wrong audience", "expired" and "the
 * post-login Action did not add the email claim" look identical from the outside.
 *
 * <p>Requests that carry no token at all are not logged: those are bots and signed-out pages, not
 * misconfiguration. The token itself is never logged.
 */
class LoggingBearerTokenEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(LoggingBearerTokenEntryPoint.class);

    private final AuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException, ServletException {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            log.warn("Rejected bearer token on {} {}: {}", request.getMethod(), request.getRequestURI(),
                    exception.getMessage());
        }
        delegate.commence(request, response, exception);
    }
}
