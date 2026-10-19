package com.drzewiecki.shitcloud.apps;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits the one application into two origins by port. The apps port answers only app documents and the
 * library mirror; the API port answers only the API, health and errors. Anything else is a 404 before
 * security or MVC see it, so an app document never appears on the API's origin and an app can never call
 * the API same-origin.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PortFence extends OncePerRequestFilter {

    /** First path segments that belong to the API port. No app may take one as its slug (see AppRegistry). */
    public static final Set<String> RESERVED_SEGMENTS = Set.of("api", "actuator", "error");

    // "/" is the app in per-app host mode, "/{slug}" and "/{slug}/" in path mode. A slug never starts with "_".
    // The reserved segments are excluded, or "/actuator" would look like an app and reach the actuator index.
    private static final Pattern APPS_PORT_PATHS = Pattern.compile(
            "/|/_lib/[^/]+|/(?!(" + String.join("|", RESERVED_SEGMENTS) + ")/?$)[a-z0-9-]+/?");
    private static final Pattern API_PORT_PATHS = Pattern.compile("/api(/.*)?|/actuator(/.*)?|/error");

    private final AppOrigin appOrigin;

    public PortFence(AppOrigin appOrigin) {
        this.appOrigin = appOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Servlet path + path info are decoded and normalized by the container: "/api/../_lib/x" is "/_lib/x".
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        Pattern allowed = appOrigin.isAppsPort(request) ? APPS_PORT_PATHS : API_PORT_PATHS;
        if (!allowed.matcher(path).matches()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
