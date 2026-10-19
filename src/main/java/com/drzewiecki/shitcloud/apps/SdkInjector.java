package com.drzewiecki.shitcloud.apps;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the SDK into an app's HTML as its first script. Inline rather than {@code <script src>}: an inline
 * script runs synchronously where it stands, so the SDK exists before any script of the app, whatever the
 * app's HTML looks like.
 */
@Component
public class SdkInjector {

    // Where the SDK goes, best first: right after <head>, else after <html>, else after the doctype.
    // "<head" must be followed by ">" or whitespace, or "<header>" would match.
    private static final Pattern HEAD = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML = Pattern.compile("<html(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCTYPE = Pattern.compile("<!doctype[^>]*>", Pattern.CASE_INSENSITIVE);

    private final String script;

    public SdkInjector() {
        try {
            String sdk = new ClassPathResource("apps/sdk.js").getContentAsString(StandardCharsets.UTF_8);
            this.script = "<script>" + sdk + "</script>";
        } catch (IOException e) {
            throw new UncheckedIOException("apps/sdk.js is missing from the classpath", e);
        }
    }

    public String inject(String html) {
        for (Pattern anchor : new Pattern[]{HEAD, HTML, DOCTYPE}) {
            Matcher matcher = anchor.matcher(html);
            if (matcher.find()) {
                return html.substring(0, matcher.end()) + script + html.substring(matcher.end());
            }
        }
        // A fragment: the browser builds <html><head> around it, and the script still comes first.
        return script + html;
    }
}
