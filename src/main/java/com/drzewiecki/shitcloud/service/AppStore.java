package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.model.AppDetail;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.DeployRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * In-memory stand-in for the {@code _apps} collection. Seeded with sample apps on startup and lost on
 * restart — enough for the GUI mockup until MongoDB arrives.
 */
@Service
public class AppStore {

    private final Map<String, AppDetail> apps = new ConcurrentHashMap<>();

    public AppStore() {
        Instant now = Instant.now();
        seed("presence-calendar", "Kalendarz obecności", "Kto jest w biurze, kto na urlopie", "Filip",
                now.minus(Duration.ofHours(2)), false, 4);
        seed("lunch-poll", "Głosowanie na lunch", "Gdzie idziemy w piątek?", "Anna",
                now.minus(Duration.ofDays(1)), false, 2);
        seed("parking-spots", "Miejsca parkingowe", "Rezerwacja miejsc pod biurem", "Tomek",
                now.minus(Duration.ofDays(5)), false, 1);
        seed("secret-santa-2025", "Losowanie prezentów 2025", "Secret Santa z zeszłego roku", "Anna",
                now.minus(Duration.ofDays(290)), true, 3);
    }

    public List<AppSummary> list() {
        return apps.values().stream()
                .map(AppDetail::app)
                .sorted(Comparator.comparing(AppSummary::lastUsedAt).reversed())
                .toList();
    }

    /**
     * Opening an app counts as using it: {@code lastUsedAt} moves and an archived app comes back.
     */
    public AppDetail open(String slug) {
        return apps.compute(slug, (key, detail) -> {
            if (detail == null) {
                throw new ResponseStatusException(NOT_FOUND, "No app " + slug);
            }
            AppSummary a = detail.app();
            return new AppDetail(
                    new AppSummary(a.slug(), a.name(), a.description(), a.owner(), Instant.now(), false, a.version()),
                    detail.html());
        });
    }

    /**
     * Same name, same slug: deploying again replaces the app and bumps its version.
     */
    public AppSummary deploy(DeployRequest request, String owner) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Name is required");
        }
        if (request.html() == null || request.html().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "HTML is required");
        }
        String slug = slugify(request.name());
        AppDetail deployed = apps.compute(slug, (key, existing) -> {
            int version = existing == null ? 1 : existing.app().version() + 1;
            AppSummary summary = new AppSummary(slug, request.name().strip(), request.description(), owner,
                    Instant.now(), false, version);
            return new AppDetail(summary, request.html());
        });
        return deployed.app();
    }

    private void seed(String slug, String name, String description, String owner, Instant lastUsedAt,
                      boolean archived, int version) {
        AppSummary summary = new AppSummary(slug, name, description, owner, lastUsedAt, archived, version);
        apps.put(slug, new AppDetail(summary, readSample(slug)));
    }

    static String readSample(String name) {
        try {
            return new ClassPathResource("sample-apps/" + name + ".html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String slugify(String name) {
        String ascii = Normalizer.normalize(name.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Name must contain letters or digits");
        }
        return slug;
    }
}
