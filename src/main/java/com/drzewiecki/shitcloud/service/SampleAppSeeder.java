package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.model.AppRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fills an empty registry with the sample apps from {@code sample-apps/}, so a fresh local database has
 * something to click on. Off unless {@code apps.seed-samples} is set (the dev profile does), and never
 * touches a registry that already holds apps.
 */
@Component
public class SampleAppSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleAppSeeder.class);

    private final MongoTemplate mongo;
    private final AppsProperties properties;

    public SampleAppSeeder(MongoTemplate mongo, AppsProperties properties) {
        this.mongo = mongo;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.seedSamples()) {
            seedIfEmpty();
        }
    }

    void seedIfEmpty() {
        long existing = mongo.estimatedCount(AppRecord.class);
        if (existing > 0) {
            return;
        }
        Instant now = Instant.now();
        List<AppRecord> samples = List.of(
                sample("presence-calendar", "Kalendarz obecności", "Kto jest w biurze, kto na urlopie", "Filip",
                        now.minus(Duration.ofHours(2)), false, 4),
                sample("lunch-poll", "Głosowanie na lunch", "Gdzie idziemy w piątek?", "Anna",
                        now.minus(Duration.ofDays(1)), false, 2),
                sample("parking-spots", "Miejsca parkingowe", "Rezerwacja miejsc pod biurem", "Tomek",
                        now.minus(Duration.ofDays(5)), false, 1),
                sample("secret-santa-2025", "Losowanie prezentów 2025", "Secret Santa z zeszłego roku", "Anna",
                        now.minus(Duration.ofDays(290)), true, 3));
        mongo.insertAll(samples);
        log.info("Seeded {} sample apps", samples.size());
    }

    private static AppRecord sample(String slug, String name, String description, String owner,
                                    Instant lastUsedAt, boolean archived, int version) {
        String html = readSample(slug);
        return new AppRecord(slug, name, description, html, owner, version, lastUsedAt, archived,
                lastUsedAt, lastUsedAt, owner);
    }

    static String readSample(String name) {
        try {
            return new ClassPathResource("sample-apps/" + name + ".html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
