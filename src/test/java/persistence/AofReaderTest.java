
package persistence;

import Persistence.AofReader;
import Persistence.AofWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AofReaderTest {

    @TempDir
    Path tempDir;

    private Path createAof(String content) throws IOException {
        Path path = tempDir.resolve("appendonly.aof");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void readsSingleCommand() throws Exception {
        Path path = createAof(
                "*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\nAnas\r\n"
        );

        try (AofReader reader = new AofReader(path)) {
            assertEquals(
                    List.of("SET", "name", "Anas"),
                    reader.readCommand()
            );
        }
    }

    @Test
    void readsMultipleCommandsInOrder() throws Exception {
        Path path = tempDir.resolve("commands.aof");

        try (AofWriter writer = new AofWriter(path)) {
            writer.append(List.of("SET", "counter", "10"));
            writer.append(List.of("INCR", "counter"));
            writer.append(List.of("GET", "counter"));
        }

        try (AofReader reader = new AofReader(path)) {
            assertEquals(
                    List.of("SET", "counter", "10"),
                    reader.readCommand()
            );

            assertEquals(
                    List.of("INCR", "counter"),
                    reader.readCommand()
            );

            assertEquals(
                    List.of("GET", "counter"),
                    reader.readCommand()
            );

            assertNull(reader.readCommand());
        }
    }

    @Test
    void returnsNullAtCleanEof() throws Exception {
        Path path = createAof(
                "*1\r\n$4\r\nPING\r\n"
        );

        try (AofReader reader = new AofReader(path)) {
            assertEquals(
                    List.of("PING"),
                    reader.readCommand()
            );

            assertNull(reader.readCommand());
            assertNull(reader.readCommand());
        }
    }

    @Test
    void returnsNullForEmptyFile() throws Exception {
        Path path = createAof("");

        try (AofReader reader = new AofReader(path)) {
            assertNull(reader.readCommand());
        }
    }

    @Test
    void readsUnicodeArguments() throws Exception {
        Path path = tempDir.resolve("unicode.aof");

        List<String> original =
                List.of("SET", "greeting", "مرحبا é 🚀");

        try (AofWriter writer = new AofWriter(path)) {
            writer.append(original);
        }

        try (AofReader reader = new AofReader(path)) {
            assertEquals(original, reader.readCommand());
            assertNull(reader.readCommand());
        }
    }

    @Test
    void readsEmptyStringArgument() throws Exception {
        Path path = tempDir.resolve("empty-value.aof");

        try (AofWriter writer = new AofWriter(path)) {
            writer.append(List.of("SET", "name", ""));
        }

        try (AofReader reader = new AofReader(path)) {
            assertEquals(
                    List.of("SET", "name", ""),
                    reader.readCommand()
            );
        }
    }

    @Test
    void readsArgumentsContainingCrLf() throws Exception {
        Path path = tempDir.resolve("multiline.aof");

        String value = "Hello\r\nWorld";
        List<String> original = List.of("SET", "message", value);

        try (AofWriter writer = new AofWriter(path)) {
            writer.append(original);
        }

        try (AofReader reader = new AofReader(path)) {
            assertEquals(original, reader.readCommand());
        }
    }

    @Test
    void throwsWhenFileDoesNotExist() {
        Path missing = tempDir.resolve("missing.aof");

        assertThrows(
                NoSuchFileException.class,
                () -> new AofReader(missing)
        );
    }

    @Test
    void rejectsMalformedRespHeader() throws Exception {
        Path path = createAof(
                "!2\r\n$3\r\nGET\r\n$4\r\nname\r\n"
        );

        try (AofReader reader = new AofReader(path)) {
            assertThrows(
                    IOException.class,
                    reader::readCommand
            );
        }
    }

    @Test
    void rejectsTruncatedCommand() throws Exception {
        Path path = createAof(
                "*2\r\n$3\r\nGET\r\n$4\r\nna"
        );

        try (AofReader reader = new AofReader(path)) {
            assertThrows(
                    EOFException.class,
                    reader::readCommand
            );
        }
    }
}
