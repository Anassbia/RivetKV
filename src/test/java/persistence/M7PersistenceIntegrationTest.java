package persistence;

import Persistence.AofReader;
import Persistence.AofReplayer;
import Persistence.AofWriter;
import Persistence.persistentCommnadExecutor;
import command.CommnadHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import protocol.response.Response;
import storage.KeyValueStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7 end-to-end persistence tests without networking: live commands -> AOF -> fresh store.
 * Requires the latest M7 code (including SET ... PXAT and the shared persistent executor).
 */
class M7PersistenceIntegrationTest {

    @TempDir Path tempDir;

    private Path aof() { return tempDir.resolve("data").resolve("appendonly.aof"); }

    private static persistentCommnadExecutor executor(KeyValueStore store, AofWriter writer) {
        return new persistentCommnadExecutor(new CommnadHandler(store), writer);
    }

    private static KeyValueStore recovered(Path file) throws IOException {
        KeyValueStore store = new KeyValueStore();
        new AofReplayer(store).replay(file);
        return store;
    }

    private static List<List<String>> written(Path file) throws IOException {
        List<List<String>> commands = new ArrayList<>();
        try (AofReader reader = new AofReader(file)) {
            List<String> command;
            while ((command = reader.readCommand()) != null) commands.add(command);
        }
        return commands;
    }

    private static long number(Response response) {
        assertInstanceOf(Response.LongValue.class, response);
        return ((Response.LongValue) response).value();
    }

    private static void ok(Response response) {
        assertEquals(new Response.SimpleString("OK"), response);
    }

    @Test
    void setGetAndUnicodeSurviveRestartWithoutLoggingGet() throws Exception {
        Path file = aof();
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            ok(live.execute(List.of("SET", "city", "Oujda")));
            ok(live.execute(List.of("set", "greeting", "سلام 🌟")));
            assertEquals(new Response.BulkString("Oujda"), live.execute(List.of("GET", "city")));
        }
        assertEquals(2, written(file).size(), "GET must not be persisted");
        KeyValueStore restored = recovered(file);
        assertEquals("Oujda", restored.get("city"));
        assertEquals("سلام 🌟", restored.get("greeting"));
    }

    @Test
    void setnxPersistsAsSetOnlyWhenItCreatesTheKey() throws Exception {
        Path file = aof();
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            assertEquals(1, number(live.execute(List.of("SETNX", "name", "first"))));
            assertEquals(0, number(live.execute(List.of("SETNX", "name", "second"))));
        }
        assertEquals(List.of(List.of("SET", "name", "first")), written(file));
        assertEquals("first", recovered(file).get("name"));
    }

    @Test
    void delPersistsOnlyOnActualDeletion() throws Exception {
        Path file = aof();
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            ok(live.execute(List.of("SET", "temporary", "hello")));
            assertEquals(1, number(live.execute(List.of("DEL", "temporary"))));
            assertEquals(0, number(live.execute(List.of("DEL", "temporary"))));
        }
        assertEquals(List.of(List.of("SET", "temporary", "hello"),
                List.of("DEL", "temporary")), written(file));
        assertNull(recovered(file).get("temporary"));
    }

    @Test
    void incrementAndDecrementWithoutTtlPersistResultingSetCommands() throws Exception {
        Path file = aof();
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            assertEquals(1, number(live.execute(List.of("INCR", "counter"))));
            assertEquals(2, number(live.execute(List.of("INCR", "counter"))));
            assertEquals(1, number(live.execute(List.of("DECR", "counter"))));
            assertEquals(-1, number(live.execute(List.of("DECR", "missing"))));
        }
        assertEquals(List.of(
                List.of("SET", "counter", "1"),
                List.of("SET", "counter", "2"),
                List.of("SET", "counter", "1"),
                List.of("SET", "missing", "-1")), written(file));
        KeyValueStore restored = recovered(file);
        assertEquals("1", restored.get("counter"));
        assertEquals("-1", restored.get("missing"));
    }

    @Test
    void counterWithTtlPersistsSingleSetPxatAndRestoresOriginalDeadline() throws Exception {
        Path file = aof();
        long deadline = System.currentTimeMillis() + 60_000L;
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            ok(live.execute(List.of("SET", "score", "10", "PXAT", Long.toString(deadline))));
            assertEquals(11, number(live.execute(List.of("INCR", "score"))));
            assertEquals(10, number(live.execute(List.of("DECR", "score"))));
        }
        assertEquals(List.of(
                List.of("SET", "score", "10", "PXAT", Long.toString(deadline)),
                List.of("SET", "score", "11", "PXAT", Long.toString(deadline)),
                List.of("SET", "score", "10", "PXAT", Long.toString(deadline))
        ), written(file), "Each counter mutation with TTL must be ONE AOF record");
        KeyValueStore restored = recovered(file);
        assertEquals("10", restored.get("score"));
        assertTrue(restored.ttl("score") >= 0, "The key must retain an expiration");
        assertTrue(restored.ttl("score") <= 60, "The expiration must not reset");
    }

    @Test
    @Timeout(5)
    void counterWithTtlDoesNotResurrectAfterDeadlineDuringRecovery() throws Exception {
        Path file = aof();
        long deadline = System.currentTimeMillis() + 1_200L;
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            ok(live.execute(List.of("SET", "score", "10", "PXAT", Long.toString(deadline))));
            assertEquals(11, number(live.execute(List.of("INCR", "score"))));
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining >= 0) Thread.sleep(remaining + 100L);
        assertNull(recovered(file).get("score"), "AOF replay must not revive an expired counter");
    }

    @Test
    void expirePersistsAbsoluteDeadlineNotRelativeSeconds() throws Exception {
        Path file = aof();
        long before = System.currentTimeMillis();
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            ok(live.execute(List.of("SET", "session", "token")));
            assertEquals(1, number(live.execute(List.of("EXPIRE", "session", "60"))));
            assertEquals(0, number(live.execute(List.of("EXPIRE", "unknown", "60"))));
        }
        List<List<String>> commands = written(file);
        assertEquals(2, commands.size());
        List<String> normalized = commands.get(1);
        assertEquals(List.of("PEXPIREAT", "session"), normalized.subList(0, 2));
        long deadline = Long.parseLong(normalized.get(2));
        assertTrue(deadline >= before + 60_000L);
        assertTrue(deadline <= System.currentTimeMillis() + 60_000L);
        assertEquals("token", recovered(file).get("session"));
    }

    @Test
    void expireZeroAndPastPexpireatDeleteAndStayDeletedAfterReplay() throws Exception {
        Path file = aof();
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            ok(live.execute(List.of("SET", "one", "1")));
            assertEquals(1, number(live.execute(List.of("EXPIRE", "one", "0"))));
            ok(live.execute(List.of("SET", "two", "2")));
            assertEquals(1, number(live.execute(List.of("PEXPIREAT", "two", "1"))));
        }
        assertNull(recovered(file).get("one"));
        assertNull(recovered(file).get("two"));
        assertTrue(written(file).stream().anyMatch(c -> c.get(0).equalsIgnoreCase("PEXPIREAT")));
    }

    @Test
    void directPexpireatPersistsOnlyOnSuccess() throws Exception {
        Path file = aof();
        long deadline = System.currentTimeMillis() + 60_000L;
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            assertEquals(0, number(live.execute(List.of("PEXPIREAT", "missing", Long.toString(deadline)))));
            ok(live.execute(List.of("SET", "key", "value")));
            assertEquals(1, number(live.execute(List.of("PEXPIREAT", "key", Long.toString(deadline)))));
        }
        assertEquals(List.of(
                List.of("SET", "key", "value"),
                List.of("PEXPIREAT", "key", Long.toString(deadline))), written(file));
    }

    @Test
    void invalidCommandsReadOnlyCommandsAndOverflowDoNotPolluteAof() throws Exception {
        Path file = aof();
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            ok(live.execute(List.of("SET", "max", Long.toString(Long.MAX_VALUE))));
            assertInstanceOf(Response.Error.class, live.execute(List.of("INCR", "max")));
            assertInstanceOf(Response.Error.class, live.execute(List.of("INCR", "max", "extra")));
            assertInstanceOf(Response.Error.class, live.execute(List.of("SET", "x")));
            assertInstanceOf(Response.Error.class, live.execute(List.of("PEXPIREAT", "max", "invalid")));
            assertInstanceOf(Response.Error.class, live.execute(List.of("NO_SUCH_COMMAND", "x")));
            live.execute(List.of("GET", "max"));
            live.execute(List.of("TTL", "max"));
        }
        assertEquals(List.of(List.of("SET", "max", Long.toString(Long.MAX_VALUE))), written(file));
        assertEquals(Long.toString(Long.MAX_VALUE), recovered(file).get("max"));
    }

    @Test
    void overwritingExpiringKeyWithPlainSetRemovesTtlAlsoAfterRecovery() throws Exception {
        Path file = aof();
        long deadline = System.currentTimeMillis() + 60_000L;
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(new KeyValueStore(), writer);
            ok(live.execute(List.of("SET", "key", "first", "PXAT", Long.toString(deadline))));
            ok(live.execute(List.of("SET", "key", "second")));
        }
        KeyValueStore restored = recovered(file);
        assertEquals("second", restored.get("key"));
        assertEquals(-1, restored.ttl("key"));
    }

    @Test
    @Timeout(15)
    void concurrentIncrementsPersistInAConsistentOrder() throws Exception {
        Path file = aof();
        KeyValueStore store = new KeyValueStore();
        int threads = 6, perThread = 35;
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try (AofWriter writer = new AofWriter(file)) {
            var live = executor(store, writer);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        Response response = live.execute(List.of("INCR", "shared"));
                        assertInstanceOf(Response.LongValue.class, response);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
        String expected = Integer.toString(threads * perThread);
        assertEquals(expected, store.get("shared"));
        assertEquals(expected, recovered(file).get("shared"));
        assertEquals(threads * perThread, written(file).size());
    }
}
