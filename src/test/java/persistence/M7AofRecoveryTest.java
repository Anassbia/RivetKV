package persistence;

import Persistence.AofReplayer;
import Persistence.AofWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.KeyValueStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Startup/replay behavior using temporary files, never the project's real data directory. */
class M7AofRecoveryTest {
    @TempDir Path tempDir;

    private Path aof() { return tempDir.resolve("appendonly.aof"); }

    @Test
    void firstStartupWithMissingFileBeginsEmpty() throws Exception {
        KeyValueStore store = new KeyValueStore();
        assertDoesNotThrow(() -> new AofReplayer(store).replay(aof()));
        assertNull(store.get("missing"));
        assertFalse(Files.exists(aof()), "Replay should not create an AOF file itself");
    }

    @Test
    void emptyFileIsValid() throws Exception {
        Files.createFile(aof());
        assertDoesNotThrow(() -> new AofReplayer(new KeyValueStore()).replay(aof()));
    }

    @Test
    void replayDoesNotAppendCommandsToTheFile() throws Exception {
        try (AofWriter writer = new AofWriter(aof())) {
            writer.append(List.of("SET", "key", "value"));
        }
        byte[] before = Files.readAllBytes(aof());
        for (int i = 0; i < 3; i++) {
            assertEquals("value", newStoreFromFile().get("key"));
        }
        assertArrayEquals(before, Files.readAllBytes(aof()));
    }

    private KeyValueStore newStoreFromFile() throws IOException {
        KeyValueStore store = new KeyValueStore();
        new AofReplayer(store).replay(aof());
        return store;
    }

    @Test
    void unknownSavedCommandFailsRecoveryRatherThanBeingIgnored() throws Exception {
        try (AofWriter writer = new AofWriter(aof())) {
            writer.append(List.of("SET", "valid", "1"));
            writer.append(List.of("UNSUPPORTED", "valid"));
        }
        IOException error = assertThrows(IOException.class, () -> newStoreFromFile());
        assertTrue(error.getMessage().contains("UNSUPPORTED"));
    }

    @Test
    void malformedResp2HeaderFailsRecovery() throws Exception {
        Files.writeString(aof(), "this is not RESP2", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> newStoreFromFile());
    }

    @Test
    void truncatedTrailingCommandFailsRecoveryNotSilentlyAccepted() throws Exception {
        try (AofWriter writer = new AofWriter(aof())) {
            writer.append(List.of("SET", "valid", "1"));
        }
        Files.writeString(aof(), "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nxy",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> newStoreFromFile());
    }

    @Test
    void invalidSavedTimestampFailsRecovery() throws Exception {
        try (AofWriter writer = new AofWriter(aof())) {
            writer.append(List.of("SET", "value", "1", "PXAT", "not-a-number"));
        }
        assertThrows(IOException.class, () -> newStoreFromFile());
    }

    @Test
    void expiredSetPxatRemovesEarlierValueDuringRecovery() throws Exception {
        try (AofWriter writer = new AofWriter(aof())) {
            writer.append(List.of("SET", "session", "old"));
            writer.append(List.of("SET", "session", "new", "PXAT", "1"));
        }
        assertNull(newStoreFromFile().get("session"));
    }
}
