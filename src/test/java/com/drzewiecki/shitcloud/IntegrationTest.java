package com.drzewiecki.shitcloud;

import com.drzewiecki.shitcloud.mongo.MongoCollections;
import com.drzewiecki.shitcloud.security.AuthProperties;
import com.drzewiecki.shitcloud.security.GuestTokenService;
import com.drzewiecki.shitcloud.security.IdpUserConverter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Full application context with MockMvc on an embedded mongod (see {@code EmbeddedMongoConfig}). Guest
 * sign-in is enabled with a test password, apps are in path mode. Every test starts with an empty registry
 * and no app data; the indexes stay.
 *
 * <p>Two ways to act as a caller:
 * <ul>
 *   <li>Guests use a real token from {@link GuestTokenService}, so the whole path (issuer routing,
 *       signature, password fingerprint, converter) is exercised.</li>
 *   <li>Identity-provider users are built by the production {@link IdpUserConverter} from a hand-made
 *       {@link Jwt}. Their decoding would need Auth0's signing keys, so it is the one step not covered here.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "auth.guest.password=" + IntegrationTest.GUEST_PASSWORD,
        "auth.guest.token-secret=integration-test-secret-0123456789abcdefghij",
        "apps.url-pattern=" + IntegrationTest.APPS_URL_PATTERN
})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final String GUEST_PASSWORD = "test-password";
    protected static final String APPS_URL_PATTERN = "http://apps.test/{slug}/";
    /** MockMvc requests arrive on port 80 (the API) unless a test puts them on this one. */
    protected static final int APPS_PORT = 8081;
    protected static final String BUILDER_EMAIL = "builder@example.com";
    protected static final String ADMIN_EMAIL = "admin@example.com";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MongoTemplate mongo;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private IdpUserConverter idpUserConverter;

    @Autowired
    private GuestTokenService guestTokenService;

    @BeforeEach
    void emptyTheRegistry() {
        mongo.remove(new Query(), MongoCollections.APPS);
        mongo.remove(new Query(), MongoCollections.APP_VERSIONS);
        mongo.remove(new Query(), MongoCollections.DOCS);
        mongo.remove(new Query(), MongoCollections.HISTORY);
    }

    protected RequestPostProcessor builder() {
        return idpUser(BUILDER_EMAIL, "Builder", authProperties.buildersGroup());
    }

    protected RequestPostProcessor admin() {
        return idpUser(ADMIN_EMAIL, "Admin", authProperties.adminsGroup());
    }

    protected RequestPostProcessor idpUser(String email, String name, String... groups) {
        Jwt jwt = Jwt.withTokenValue("idp-token")
                .header("alg", "none")
                .issuer(authProperties.issuerUri())
                .subject("google-oauth2|" + email)
                .claim(authProperties.idClaim(), email)
                .claim(authProperties.nameClaim(), name)
                .claim(authProperties.groupsClaim(), List.of(groups))
                .build();
        AbstractAuthenticationToken caller = idpUserConverter.convert(jwt);
        return authentication(caller);
    }

    protected RequestPostProcessor guest(String name) {
        String token = guestTokenService.issue(name).token();
        return bearer(token);
    }

    /** Sends the request to the apps port, as the second connector would receive it. */
    protected static RequestPostProcessor appsPort() {
        return request -> {
            request.setLocalPort(APPS_PORT);
            request.setServerPort(APPS_PORT);
            return request;
        };
    }

    protected static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
