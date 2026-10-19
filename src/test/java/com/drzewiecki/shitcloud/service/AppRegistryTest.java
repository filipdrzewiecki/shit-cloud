package com.drzewiecki.shitcloud.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppRegistryTest {

    @Test
    void slugify_stripsPolishDiacritics() {
        assertThat(AppRegistry.slugify("Kalendarz obecności")).isEqualTo("kalendarz-obecnosci");
        assertThat(AppRegistry.slugify("Łódź — żółć")).isEqualTo("lodz-zolc");
    }

    @Test
    void slugify_collapsesPunctuationAndTrimsDashes() {
        assertThat(AppRegistry.slugify("  Lunch poll!!! (v2)  ")).isEqualTo("lunch-poll-v2");
    }

    @Test
    void slugify_withoutLettersOrDigits_isRejected() {
        assertThatThrownBy(() -> AppRegistry.slugify("!!!")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void slugify_refusesNamesThatWouldCollideWithTheApi() {
        assertThatThrownBy(() -> AppRegistry.slugify("Actuator")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> AppRegistry.slugify("API!")).isInstanceOf(ResponseStatusException.class);
        assertThat(AppRegistry.slugify("API notes")).isEqualTo("api-notes");
    }
}
