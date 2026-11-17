package persistence;

import Persistence.AofWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protocol.Parser.Resp2Parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AofWriterTest {

    @TempDir
    Path tempDir;

    private Path aofFile() {
        return tempDir.resolve("nested").resolve("data").resolve("appendonly.aof");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<List<String>> readAll(Path file) throws IOException {
        List<List<String>> commands = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            Resp2Parser parser = new Resp2Parser(in);
            List<String> command;
            while ((command = parser.readCommand()) != null) {
                commands.add(command);
            }
        }
        return commands;
    }

    @Test
    void createsMissingParentDirectoriesAndEncodesExactResp2Bytes() throws Exception {
        Path file = aofFile();
        try (AofWriter writer = new AofWriter(file)) {
            writer.append(List.of("SET", "name", "Anas"));
        }
        assertTrue(Files.isRegularFile(file));
        assertArrayEquals(bytes("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\nAnas\r\n"),
                Files.readAllBytes(file));
    }

    @Test
    void reopeningWriterAppendsInsteadOfOverwriting() throws Exception {
        Path file = aofFile();
        try (AofWriter writer = new AofWriter(file)) {
            writer.append(List.of("SET", "counter", "10"));
        }
        try (AofWriter writer = new AofWriter(file)) {
            writer.append(List.of("INCR", "counter"));
        }
        assertEquals(List.of(List.of("SET", "counter", "10"), List.of("INCR", "counter")),
                readAll(file));
    }

    @Test
    void multipleAppendsProduceSeparateParseableCommands() throws Exception {
        Path file = aofFile();
        try (AofWriter writer = new AofWriter(file)) {
            writer.append(List.of("SET", "name", "Anas"));
            writer.append(List.of("EXPIRE", "name", "60"));
            writer.append(List.of("DEL", "name"));
        }
        assertEquals(List.of(
                List.of("SET", "name", "Anas"),
                List.of("EXPIRE", "name", "60"),
                List.of("DEL", "name")), readAll(file));
    }

    @Test
    void utf8LengthsAndEmbeddedCrLfRoundTripCorrectly() throws Exception {
        Path file = aofFile();
        try (AofWriter writer = new AofWriter(file)) {
            writer.append(List.of("SET", "clé", "été 🚀\r\nsecond line"));
        }
        assertEquals(List.of(List.of("SET", "clé", "été 🚀\r\nsecond line")), readAll(file));
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .contains("$4\r\nclé\r\n")); // clé is 4 bytes in UTF-8
    }

    @Test
    void emptyStringArgumentIsAllowed() throws Exception {
        Path file = aofFile();
        try (AofWriter writer = new AofWriter(file)) {
            writer.append(List.of("SET", "empty", ""));
        }
        assertEquals(List.of(List.of("SET", "empty", "")), readAll(file));
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .endsWith("$0\r\n\r\n"));
    }

    @Test
    void nullCommandIsRejectedWithoutWritingBytes() throws Exception {
        Path file = aofFile();
        try (AofWriter writer = new AofWriter(file)) {
            assertThrows(IllegalArgumentException.class, () -> writer.append(null));
        }
        assertEquals(0L, Files.size(file));
    }

    @Test
    void emptyCommandListIsRejectedWithoutWritingBytes() throws Exception {
        Path file = aofFile();
        try (AofWriter writer = new AofWriter(file)) {
            assertThrows(IllegalArgumentException.class, () -> writer.append(List.of()));
        }
        assertEquals(0L, Files.size(file));
    }

    @Test
    void nullArgumentIsRejectedWithoutPartialRecord() throws Exception {
        Path file = aofFile();
        try (AofWriter writer = new AofWriter(file)) {
            writer.append(List.of("SET", "good", "1"));
            long before = Files.size(file);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.append(Arrays.asList("SET", "bad", null)));
            assertEquals(before, Files.size(file), "Failed validation must not append a broken RESP record");
        }
        assertEquals(List.of(List.of("SET", "good", "1")), readAll(file));
    }

    @Test
    void concurrentAppendsProduceCompleteNonInterleavedRecords() throws Exception {
        Path file = aofFile();
        final int threads = 6;
        final int commandsPerThread = 40;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<List<String>> expected = new HashSet<>();
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < commandsPerThread; i++) {
                expected.add(List.of("SET", "thread:" + t + ":" + i, "value " + i));
            }
        }

        try (AofWriter writer = new AofWriter(file)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                Callable<Void> task = () -> {
                    start.await();
                    for (int i = 0; i < commandsPerThread; i++) {
                        writer.append(List.of("SET", "thread:" + threadId + ":" + i, "value " + i));
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        List<List<String>> actual = readAll(file);
        assertEquals(threads * commandsPerThread, actual.size());
        assertEquals(expected, new HashSet<>(actual));
    }

    @Test
    void appendAfterCloseFailsInsteadOfSilentlyLosingData() throws Exception {
        Path file = aofFile();
        AofWriter writer = new AofWriter(file);
        writer.close();
        assertThrows(IOException.class, () -> writer.append(List.of("SET", "key", "value")));
    }
}
