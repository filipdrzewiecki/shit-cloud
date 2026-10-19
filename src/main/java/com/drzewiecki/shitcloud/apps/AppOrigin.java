package com.drzewiecki.shitcloud.apps;

import com.drzewiecki.shitcloud.service.AppsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where apps live, read from {@code apps.url-pattern}. The position of {@code {slug}} picks the mode:
 * <ul>
 *   <li>in the host ({@code http://{slug}.apps.localhost:8081/}) — one origin per app, apps cannot read
 *       each other's storage. Needs a wildcard domain.</li>
 *   <li>as the path ({@code https://apps.example/{slug}/}) — one origin shared by all apps. Works on a single
 *       generated domain; the platform's own origin is still separate.</li>
 * </ul>
 */
@Component
public class AppOrigin {

    static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private static final String PLACEHOLDER = "{slug}";
    private static final String MARKER = "slug-marker-0";

    private final String pattern;
    private final int port;
    private final boolean perAppHost;
    /** In per-app host mode: the host after the slug, e.g. {@code .apps.localhost}, without the port. */
    private final String hostSuffix;

    @Autowired
    public AppOrigin(AppsProperties properties) {
        this(properties.urlPattern(), properties.port());
    }

    AppOrigin(String pattern, int port) {
        this.pattern = pattern;
        this.port = port;
        if (pattern == null || !pattern.contains(PLACEHOLDER)
                || pattern.indexOf(PLACEHOLDER) != pattern.lastIndexOf(PLACEHOLDER)) {
            throw invalid(pattern, "it must contain {slug} exactly once");
        }
        URI uri;
        try {
            uri = URI.create(pattern.replace(PLACEHOLDER, MARKER));
        } catch (IllegalArgumentException e) {
            throw invalid(pattern, "it is not a URL");
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) || uri.getHost() == null) {
            throw invalid(pattern, "it must be an absolute http(s) URL");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid(pattern, "it must not have a query or a fragment");
        }
        String host = uri.getHost();
        if (host.contains(MARKER)) {
            if (!host.startsWith(MARKER + ".") || !"/".equals(uri.getPath())) {
                throw invalid(pattern, "in the host it must be the first label, and the path must be /");
            }
            this.perAppHost = true;
            this.hostSuffix = host.substring(MARKER.length());
        } else {
            if (!("/" + MARKER + "/").equals(uri.getPath())) {
                throw invalid(pattern, "in the path it must be the whole path: /{slug}/");
            }
            this.perAppHost = false;
            this.hostSuffix = null;
        }
    }

    /** The URL the platform frames for this app. */
    public String urlFor(String slug) {
        return pattern.replace(PLACEHOLDER, slug);
    }

    public boolean perAppHost() {
        return perAppHost;
    }

    /** True when the request came in on the apps port. */
    public boolean isAppsPort(HttpServletRequest request) {
        return request.getLocalPort() == port;
    }

    /** In per-app host mode: the slug named by the request's host. Empty in path mode or for any other host. */
    public Optional<String> slugFromHost(HttpServletRequest request) {
        if (!perAppHost) {
            return Optional.empty();
        }
        String host = request.getServerName().toLowerCase(Locale.ROOT);
        if (!host.endsWith(hostSuffix)) {
            return Optional.empty();
        }
        String slug = host.substring(0, host.length() - hostSuffix.length());
        return SLUG.matcher(slug).matches() ? Optional.of(slug) : Optional.empty();
    }

    private static IllegalStateException invalid(String pattern, String reason) {
        return new IllegalStateException("apps.url-pattern (APPS_URL_PATTERN) '" + pattern + "' is invalid: "
                + reason + ". Examples: https://apps.example.com/{slug}/, https://{slug}.apps.example.com/");
    }
}
