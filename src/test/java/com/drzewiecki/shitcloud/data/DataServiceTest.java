package com.drzewiecki.shitcloud.data;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class DataServiceTest {

    @Test
    void newIds_areTimeOrderedHexLikeMongoObjectIds() {
        String id = DataService.newId();

        assertThat(id).matches("[0-9a-f]{24}");
        long seconds = Long.parseLong(id.substring(0, 8), 16);
        assertThat(seconds).isCloseTo(Instant.now().getEpochSecond(), offset(5L));
        assertThat(DataService.newId()).isNotEqualTo(id);
    }
}
