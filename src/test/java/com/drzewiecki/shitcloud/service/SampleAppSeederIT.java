package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.AppRecord;
import com.drzewiecki.shitcloud.model.DeployRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SampleAppSeederIT extends IntegrationTest {

    @Autowired
    private AppRegistry registry;

    private SampleAppSeeder seeder() {
        AppsProperties enabled = new AppsProperties(Duration.ofDays(90), DataSize.ofMegabytes(5), true);
        return new SampleAppSeeder(mongo, enabled);
    }

    @Test
    void emptyRegistry_getsTheSamplesOnce() {
        seeder().seedIfEmpty();
        seeder().seedIfEmpty();

        assertThat(mongo.findAll(AppRecord.class))
                .extracting(AppRecord::slug)
                .containsExactlyInAnyOrder("presence-calendar", "lunch-poll", "parking-spots", "secret-santa-2025");
    }

    @Test
    void registryWithApps_isLeftAlone() {
        registry.deploy(new DeployRequest("Moja apka", null, "<p>mine</p>"), "anna@example.com");

        seeder().seedIfEmpty();

        assertThat(mongo.findAll(AppRecord.class)).extracting(AppRecord::slug).containsExactly("moja-apka");
    }

    @Test
    void outsideTheDevProfile_seedingIsOff() {
        // The test context runs without the dev profile: startup must not have seeded anything.
        assertThat(mongo.findById("lunch-poll", AppRecord.class)).isNull();
    }
}
