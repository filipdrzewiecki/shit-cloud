package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.model.GuestLoginRequest;
import com.drzewiecki.shitcloud.model.GuestToken;
import com.drzewiecki.shitcloud.security.GuestTokenService;
import com.drzewiecki.shitcloud.security.LoginAttemptLimiter;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class GuestLoginService {

    static final int MAX_NAME_LENGTH = 40;

    private final GuestTokenService guestTokens;
    private final LoginAttemptLimiter limiter;

    public GuestLoginService(GuestTokenService guestTokens, LoginAttemptLimiter limiter) {
        this.guestTokens = guestTokens;
        this.limiter = limiter;
    }

    /**
     * The limiter is checked before the password, so a blocked IP learns nothing more, not even whether
     * its next guess would have been right.
     */
    public GuestToken signIn(GuestLoginRequest request, String clientIp) {
        if (!guestTokens.enabled()) {
            throw new ResponseStatusException(NOT_FOUND, "Guest sign-in is not enabled");
        }
        if (limiter.blocked(clientIp)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Too many failed attempts");
        }
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "Name must be 1-" + MAX_NAME_LENGTH + " characters");
        }
        if (!guestTokens.passwordMatches(request.password())) {
            limiter.recordFailure(clientIp);
            throw new ResponseStatusException(UNAUTHORIZED, "Wrong password");
        }
        limiter.reset(clientIp);
        return guestTokens.issue(name);
    }
}
