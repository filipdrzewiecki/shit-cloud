package com.drzewiecki.shitcloud.apps;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkInjectorTest {

    private final SdkInjector injector = new SdkInjector();

    @Test
    void goesRightAfterTheHeadTag() {
        String html = injector.inject("<!doctype html><html><head><meta charset=\"utf-8\"><script>app()</script>");

        assertThat(html).startsWith("<!doctype html><html><head><script>// ShitCloud SDK");
        assertThat(html.indexOf("window, 'shitcloud'")).isLessThan(html.indexOf("app()"));
    }

    @Test
    void headWithAttributesAndUppercase_isFound() {
        String html = injector.inject("<HTML><HEAD lang=\"pl\"><title>x</title>");

        assertThat(html).startsWith("<HTML><HEAD lang=\"pl\"><script>");
    }

    @Test
    void headerElement_isNotMistakenForHead() {
        String html = injector.inject("<html><body><header>top</header><script>app()</script>");

        assertThat(html).startsWith("<html><script>");
    }

    @Test
    void withoutHead_goesAfterTheHtmlTag() {
        assertThat(injector.inject("<html lang=\"en\"><body>x")).startsWith("<html lang=\"en\"><script>");
    }

    @Test
    void withOnlyADoctype_goesAfterIt() {
        // Before the doctype it would push the page into quirks mode.
        assertThat(injector.inject("<!DOCTYPE html><p>x</p>")).startsWith("<!DOCTYPE html><script>");
    }

    @Test
    void fragment_getsTheSdkFirst() {
        assertThat(injector.inject("<p>x</p><script>app()</script>")).startsWith("<script>").endsWith("<p>x</p><script>app()</script>");
    }

    @Test
    void theAppsOwnHtml_isKeptUnchanged() {
        String original = "<!doctype html><html><head><title>t</title></head><body>b</body></html>";

        String injected = injector.inject(original);

        assertThat(injected.replaceFirst("(?s)<script>.*?</script>", "")).isEqualTo(original);
    }
}
