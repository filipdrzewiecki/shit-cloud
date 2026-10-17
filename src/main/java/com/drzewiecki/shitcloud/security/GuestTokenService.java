package com.drzewiecki.shitcloud.security;

import com.drzewiecki.shitcloud.model.GuestToken;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * The API is its own token issuer for guests. Guest tokens are short-lived HS256 JWTs signed with
 * {@code auth.guest.token-secret} and validated by the same resource-server machinery as identity-provider
 * tokens, so every endpoint sees one kind of caller.
 *
 * <p>Each token carries a fingerprint of the password it was issued under. Changing the password changes
 * the fingerprint, which signs every guest out at once: rotation without a blocklist.
 */
@Service
public class GuestTokenService {

    public static final String ISSUER = "shitcloud-guest";
    public static final String NAME_CLAIM = "name";
    public static final String SUBJECT_PREFIX = "guest:";

    private static final String PASSWORD_VERSION_CLAIM = "pwv";
    private static final String HMAC = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;

    private final AuthProperties.Guest guest;
    private final String audience;
    private final SecretKey key;
    private final String passwordVersion;

    public GuestTokenService(AuthProperties properties) {
        this.guest = properties.guest();
        this.audience = properties.audience();
        if (!guest.enabled()) {
            this.key = null;
            this.passwordVersion = null;
            return;
        }
        byte[] secret = guest.tokenSecret() == null
                ? new byte[0]
                : guest.tokenSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("GUEST_PASSWORD is set, so GUEST_TOKEN_SECRET must be at least "
                    + MIN_SECRET_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(secret, HMAC);
        this.passwordVersion = fingerprint(guest.password());
    }

    public boolean enabled() {
        return key != null;
    }

    /** Constant-time, so response timing does not reveal how much of a guess was right. */
    public boolean passwordMatches(String candidate) {
        if (!enabled() || candidate == null) {
            return false;
        }
        byte[] expected = sha256(guest.password());
        byte[] actual = sha256(candidate);
        return MessageDigest.isEqual(expected, actual);
    }

    public GuestToken issue(String name) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(guest.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(audience))
                .subject(SUBJECT_PREFIX + name)
                .claim(NAME_CLAIM, name)
                .claim(PASSWORD_VERSION_CLAIM, passwordVersion)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new GuestToken(token, expiresAt);
    }

    public JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(ISSUER);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience));
        OAuth2TokenValidator<Jwt> currentPassword = new JwtClaimValidator<String>(
                PASSWORD_VERSION_CLAIM, passwordVersion::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, audienceValidator, currentPassword));
        return decoder;
    }

    /** Keyed, so the fingerprint inside a token says nothing about the password without the secret. */
    private String fingerprint(String password) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            byte[] digest = mac.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
