
import Persistence.AofReplayer;
import Persistence.AofWriter;
import Persistence.persistentCommnadExecutor;

import command.CommnadHandler;
import server.ClientSessionHandler;
import server.RedisServer;
import storage.KeyValueStore;

import java.io.IOException;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws IOException {

        Path aofPath = Path.of("data", "appendonly.aof");
        KeyValueStore kvs = new KeyValueStore();

        AofReplayer replayer = new AofReplayer(kvs);
        replayer.replay(aofPath);

        try (AofWriter writer = new AofWriter(aofPath)) {

            CommnadHandler handler = new CommnadHandler(kvs);

            persistentCommnadExecutor executor =new persistentCommnadExecutor(handler, writer);

            ClientSessionHandler sessionHandler = new ClientSessionHandler(executor);

            RedisServer redisServer = new RedisServer(sessionHandler);

            redisServer.start();
        }
    }
}
