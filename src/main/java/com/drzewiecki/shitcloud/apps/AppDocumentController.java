package com.drzewiecki.shitcloud.apps;

import com.drzewiecki.shitcloud.service.AppRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serves an app's HTML as a document of its own, on the apps port only ({@link PortFence}). Public by slug:
 * the document is just the stored HTML, and the app holds no data yet (app data will sit behind the
 * signed-in host page). Opening it does not count as using the app; the host page's API call does that.
 */
@RestController
@PreAuthorize("permitAll()")
public class AppDocumentController {

    private final AppRegistry appRegistry;
    private final AppOrigin appOrigin;
    private final SdkInjector sdkInjector;
    private final String contentSecurityPolicy;

    public AppDocumentController(AppRegistry appRegistry, AppOrigin appOrigin, SdkInjector sdkInjector,
                                 @Value("${cors.allowed-origins}") List<String> platformOrigins) {
        this.appRegistry = appRegistry;
        this.appOrigin = appOrigin;
        this.sdkInjector = sdkInjector;
        this.contentSecurityPolicy = contentSecurityPolicy(platformOrigins);
    }

    /** Per-app host mode: the host names the app. */
    @GetMapping("/")
    public ResponseEntity<String> byHost(HttpServletRequest request) {
        requireAppsPort(request);
        String slug = appOrigin.slugFromHost(request).orElseThrow(AppDocumentController::notFound);
        return document(slug);
    }

    /** Path mode: the first path segment names the app. */
    @GetMapping("/{slug}/")
    public ResponseEntity<String> byPath(@PathVariable String slug, HttpServletRequest request) {
        requireAppsPort(request);
        if (appOrigin.perAppHost()) {
            throw notFound();
        }
        return document(slug);
    }

    /** Path mode without the trailing slash: redirect, so relative URLs inside the app resolve under its path. */
    @GetMapping("/{slug}")
    public ResponseEntity<Void> addTrailingSlash(@PathVariable String slug, HttpServletRequest request) {
        requireAppsPort(request);
        if (appOrigin.perAppHost()) {
            throw notFound();
        }
        // Set as a plain header: sendRedirect would make it absolute with the container's idea of the scheme,
        // which is http behind Railway's proxy.
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/" + slug + "/").build();
    }

    private ResponseEntity<String> document(String slug) {
        String html = appRegistry.html(slug);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header("Content-Security-Policy", contentSecurityPolicy)
                .header("Referrer-Policy", "no-referrer")
                // A redeploy must show at once.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(sdkInjector.inject(html));
    }

    /**
     * Apps run anything they like inside their own origin, but cannot send what they hold anywhere: no
     * network calls, no form posts, nothing loaded from other origins. Only the platform may frame them.
     * {@code 'unsafe-eval'} is for Alpine, which evaluates its attributes.
     */
    private static String contentSecurityPolicy(List<String> platformOrigins) {
        return String.join("; ",
                "default-src 'self'",
                "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
                "style-src 'self' 'unsafe-inline'",
                "img-src 'self' data: blob:",
                "font-src 'self' data:",
                "media-src 'self' data: blob:",
                "connect-src 'none'",
                "form-action 'none'",
                "base-uri 'none'",
                "object-src 'none'",
                "frame-ancestors " + String.join(" ", platformOrigins));
    }

    // PortFence already keeps these paths off the API port; "/{slug}" would otherwise also match "/api".
    private void requireAppsPort(HttpServletRequest request) {
        if (!appOrigin.isAppsPort(request)) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
