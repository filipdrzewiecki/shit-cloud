package com.drzewiecki.shitcloud.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat package is meant to be copied into other projects as it is, so it must not depend on the rest of
 * ShitCloud. ShitCloud's tools and endpoints live in {@code buildchat}.
 */
class ChatPackageBoundaryTest {

    private static final Path CHAT = Path.of("src/main/java/com/drzewiecki/shitcloud/chat");

    @Test
    void chatPackage_importsNothingElseFromShitCloud() throws IOException {
        List<String> offending;
        try (Stream<Path> files = Files.walk(CHAT)) {
            offending = files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(ChatPackageBoundaryTest::lines)
                    .filter(line -> line.startsWith("import com.drzewiecki.shitcloud.")
                            || line.startsWith("import static com.drzewiecki.shitcloud."))
                    .filter(line -> !line.contains("com.drzewiecki.shitcloud.chat."))
                    .toList();
        }
        assertThat(offending).isEmpty();
    }

    @Test
    void chatPackage_isFound() {
        assertThat(CHAT.resolve("ChatEngine.java")).exists();
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream().map(String::strip);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
