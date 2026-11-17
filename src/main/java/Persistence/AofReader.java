package Persistence;

import protocol.Parser.Resp2Parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class AofReader implements AutoCloseable {
    private final InputStream inputStream;
    private final Resp2Parser parser;

    public AofReader(Path path) throws IOException {
        this.inputStream = Files.newInputStream(path, StandardOpenOption.READ);
        this.parser = new Resp2Parser(inputStream);
    }

    public List<String> readCommand() throws IOException {
        return parser.readCommand();
    }


    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
