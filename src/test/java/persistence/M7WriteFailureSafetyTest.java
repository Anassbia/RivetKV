package persistence;

import Persistence.AofWriter;
import Persistence.persistentCommnadExecutor;
import command.CommnadHandler;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.KeyValueStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KNOWN RELIABILITY GAP. Intentionally disabled until the write-failure policy is fixed.
 * Current implementation mutates memory before append(); a failed append can leave
 * a value in memory that cannot be recovered from disk.
 */
class M7WriteFailureSafetyTest {
    @TempDir Path tempDir;

    @Disabled("Known M7 gap: failed AOF append occurs after the in-memory mutation; define and implement a safe failure policy first")
    @Test
    void failedAppendMustNotLeaveUnloggedMutationInMemory() throws Exception {
        Path file = tempDir.resolve("appendonly.aof");
        KeyValueStore store = new KeyValueStore();
        AofWriter writer = new AofWriter(file);
        writer.close(); // deterministic way to force the subsequent append to fail
        persistentCommnadExecutor executor = new persistentCommnadExecutor(
                new CommnadHandler(store), writer);

        assertThrows(IOException.class,
                () -> executor.execute(List.of("SET", "unlogged", "value")));
        assertNull(store.get("unlogged"),
                "This currently FAILS: memory was changed before the AOF write failed");
    }
}
