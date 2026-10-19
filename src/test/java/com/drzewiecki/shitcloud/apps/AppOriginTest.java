package com.drzewiecki.shitcloud.apps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppOriginTest {

    @Test
    void pathMode_putsTheSlugInThePath() {
        AppOrigin origin = new AppOrigin("https://apps.example.com/{slug}/", 8081);

        assertThat(origin.perAppHost()).isFalse();
        assertThat(origin.urlFor("lunch-poll")).isEqualTo("https://apps.example.com/lunch-poll/");
        assertThat(origin.slugFromHost(request("lunch-poll.apps.example.com", 8081))).isEmpty();
    }

    @Test
    void hostMode_putsTheSlugInTheHost() {
        AppOrigin origin = new AppOrigin("http://{slug}.apps.localhost:8081/", 8081);

        assertThat(origin.perAppHost()).isTrue();
        assertThat(origin.urlFor("lunch-poll")).isEqualTo("http://lunch-poll.apps.localhost:8081/");
    }

    @Test
    void hostMode_readsTheSlugFromTheHost() {
        AppOrigin origin = new AppOrigin("http://{slug}.apps.localhost:8081/", 8081);

        assertThat(origin.slugFromHost(request("lunch-poll.apps.localhost", 8081))).contains("lunch-poll");
        assertThat(origin.slugFromHost(request("Lunch-Poll.APPS.localhost", 8081))).contains("lunch-poll");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "apps.localhost",                  // no slug at all
            "localhost",                       // another host
            "a.b.apps.localhost",              // two labels, not a slug
            "lunch_poll.apps.localhost",       // not a slug character
            "-x.apps.localhost",               // slugs never start with a dash
            "lunch-poll.apps.localhost.evil"   // suffix does not end the host
    })
    void hostMode_ignoresHostsThatNameNoApp(String host) {
        AppOrigin origin = new AppOrigin("http://{slug}.apps.localhost:8081/", 8081);

        assertThat(origin.slugFromHost(request(host, 8081))).isEmpty();
    }

    @Test
    void isAppsPort_looksAtTheLocalPortOnly() {
        AppOrigin origin = new AppOrigin("https://apps.example.com/{slug}/", 8081);

        assertThat(origin.isAppsPort(request("apps.example.com", 8081))).isTrue();
        assertThat(origin.isAppsPort(request("apps.example.com", 8080))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "${APPS_URL_PATTERN}",                        // the variable is not set
            "https://apps.example.com/",                  // no placeholder
            "https://{slug}.apps.example.com/{slug}/",    // placeholder twice
            "apps.example.com/{slug}/",                   // not absolute
            "ftp://apps.example.com/{slug}/",             // not http(s)
            "https://apps.example.com/apps/{slug}/",      // path must be exactly /{slug}/
            "https://apps.example.com/{slug}",            // trailing slash missing
            "https://x{slug}.apps.example.com/",          // slug must be a whole label
            "https://{slug}.apps.example.com/index.html", // host mode needs path /
            "https://apps.example.com/{slug}/?a=b"        // no query
    })
    void invalidPatterns_failAtStartup(String pattern) {
        assertThatThrownBy(() -> new AppOrigin(pattern, 8081))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apps.url-pattern");
    }

    private static MockHttpServletRequest request(String host, int localPort) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setServerName(host);
        request.setLocalPort(localPort);
        return request;
    }
}
