package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.model.GuestLoginRequest;
import com.drzewiecki.shitcloud.model.GuestToken;
import com.drzewiecki.shitcloud.service.GuestLoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exchanges the shared password for a guest token. Public on purpose: this is how a token is obtained.
 */
@RestController
@RequestMapping("/api/auth/guest")
@PreAuthorize("permitAll()")
public class GuestLoginController {

    private final GuestLoginService guestLoginService;

    public GuestLoginController(GuestLoginService guestLoginService) {
        this.guestLoginService = guestLoginService;
    }

    @PostMapping
    public GuestToken signIn(@RequestBody GuestLoginRequest request, HttpServletRequest http) {
        String clientIp = clientIp(http);
        return guestLoginService.signIn(request, clientIp);
    }

    /**
     * Railway's proxy appends the address it saw to {@code X-Forwarded-For}, so the last entry is the one
     * a client cannot forge; anything before it is whatever the client chose to send. Without the header
     * (local development) the socket address is the client.
     */
    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return http.getRemoteAddr();
        }
        String[] hops = forwarded.split(",");
        return hops[hops.length - 1].strip();
    }
}
