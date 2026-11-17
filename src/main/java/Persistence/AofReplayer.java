
package Persistence;

import Persistence.AofReader;
import command.CommnadHandler;
import protocol.response.Response;
import storage.KeyValueStore;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

public class AofReplayer {

    private final CommnadHandler handler;

    public AofReplayer(KeyValueStore kvs) {
        this.handler = new CommnadHandler(kvs);
    }

    public void replay(Path path) throws IOException {
        try (AofReader reader = new AofReader(path)) {
            List<String> command;
            while ((command = reader.readCommand()) != null) {
                Response response = handler.handle(command);
                if (response instanceof Response.Error(String message)) {
                    throw new IOException(
                            "AOF recovery failed on command "
                                    + command + ": " + message
                    );
                }
            }
        } catch (NoSuchFileException e) {
            // Start with an empty database.
        }
    }
}
