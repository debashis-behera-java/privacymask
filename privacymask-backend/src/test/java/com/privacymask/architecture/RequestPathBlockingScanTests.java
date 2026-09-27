package com.privacymask.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guardrail: the reactive request path must stay non-blocking. Scans
 * production sources (tests may block freely) for blocking constructs. Kept
 * deliberately narrow to avoid false positives - each pattern below is
 * forbidden in {@code src/main} by project rule.
 */
class RequestPathBlockingScanTests {

    private static final List<String> FORBIDDEN = List.of(
            ".block(",
            ".blockFirst(",
            ".blockLast(",
            "Thread.sleep(",
            "new Thread(",
            "Executors.");

    @Test
    void productionCodeContainsNoBlockingConstructs() throws IOException {
        Path sources = Path.of(System.getProperty("user.dir")).resolve("src/main/java");
        assertThat(Files.isDirectory(sources))
                .as("expected module sources at %s", sources.toAbsolutePath())
                .isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    List<String> lines = Files.readAllLines(path);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (String forbidden : FORBIDDEN) {
                            if (line.contains(forbidden)) {
                                violations.add(path.getFileName() + ":" + (i + 1) + " -> "
                                        + line.trim());
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        assertThat(violations).as("blocking constructs in reactive request path").isEmpty();
    }
}
