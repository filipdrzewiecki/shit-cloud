package com.drzewiecki.shitcloud.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.List;

import static com.drzewiecki.shitcloud.security.TestAuthProperties.NS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdpUserConverterTest {

    private final IdpUserConverter converter = new IdpUserConverter(TestAuthProperties.withGuest("meat"));

    @Test
    void builder_getsTheBuilderRoleOnly() {
        UserAuthentication auth = convert(token("anna@example.com", "Anna", List.of("ShitCloud Builder")));

        CurrentUser user = auth.getUser();
        assertThat(user.id()).isEqualTo("anna@example.com");
        assertThat(user.displayName()).isEqualTo("Anna");
        assertThat(user.builder()).isTrue();
        assertThat(user.admin()).isFalse();
        assertThat(user.guest()).isFalse();
        assertThat(authorities(auth)).containsExactly(Roles.BUILDER);
    }

    @Test
    void admin_isAlsoABuilder() {
        UserAuthentication auth = convert(token("filip@example.com", "Filip", List.of("ShitCloud Admin")));

        assertThat(auth.getUser().builder()).isTrue();
        assertThat(auth.getUser().admin()).isTrue();
        assertThat(authorities(auth)).containsExactlyInAnyOrder(Roles.BUILDER, Roles.ADMIN);
    }

    @Test
    void noKnownGroup_meansNoRole() {
        UserAuthentication auth = convert(token("tomek@example.com", "Tomek", List.of("Something Else")));

        assertThat(auth.getUser().builder()).isFalse();
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void missingGroupsClaim_meansNoRole() {
        UserAuthentication auth = convert(token("tomek@example.com", "Tomek", null));

        assertThat(auth.getUser().groups()).isEmpty();
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void missingName_fallsBackToTheId() {
        UserAuthentication auth = convert(token("anna@example.com", null, List.of()));

        assertThat(auth.getUser().displayName()).isEqualTo("anna@example.com");
    }

    @Test
    void missingIdClaim_isRejected() {
        Jwt jwt = token(null, "Anna", List.of("ShitCloud Admin"));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining(NS + "email");
    }

    private UserAuthentication convert(Jwt jwt) {
        return (UserAuthentication) converter.convert(jwt);
    }

    private static List<String> authorities(UserAuthentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt token(String email, String name, List<String> groups) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("google-oauth2|1");
        if (email != null) {
            builder.claim(NS + "email", email);
        }
        if (name != null) {
            builder.claim(NS + "name", name);
        }
        if (groups != null) {
            builder.claim(NS + "groups", groups);
        }
        return builder.build();
    }
}
