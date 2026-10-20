package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A context of its own with a tiny export cap, so the cap is reached with three documents. */
@TestPropertySource(properties = "data.max-export-documents=2")
class ExportCapIT extends IntegrationTest {

    @Autowired
    private DataService data;

    @Autowired
    private AppRegistry registry;

    @Test
    void anAppOverTheCap_cannotBeDownloaded() {
        registry.deploy(new DeployRequest("Duza", null, "<p>x</p>"), BUILDER_EMAIL);
        for (String id : new String[]{"a", "b", "c"}) {
            data.set("duza", "items", id, new DataWrite(Map.of("n", 1), null), "guest:Anna");
        }

        assertThatThrownBy(() -> data.export("duza"))
                .isInstanceOfSatisfying(DataException.class,
                        e -> assertThat(e.code()).isEqualTo(DataException.TOO_LARGE));
    }
}
