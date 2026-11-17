package Persistence;

import protocol.Parser.CommandParser;
import protocol.Parser.Resp2Parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class AofWriter implements AutoCloseable {
    private final OutputStream os;

    public AofWriter(Path path) throws IOException {
        if(path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        this.os=Files.newOutputStream(path, StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }

    @Override
    public synchronized void  close() throws IOException {
        this.os.close();
    }

    public synchronized void append(List<String> command) throws IOException {
        if(!(command == null || command.isEmpty())){
            StringBuilder byteCommand = new StringBuilder();
            //1.header part
            byteCommand.append("*").append(command.size()).append("\r\n");
            //2.body

            for(int i = 0 ; i < command.size(); i++){
                String argument = command.get(i);
                if(argument == null){
                    throw new IllegalArgumentException("Command argument cannot be null");
                }
                byte[] bytes =  argument.getBytes(StandardCharsets.UTF_8);
                int length = bytes.length;

                byteCommand.append("$").append(length).append("\r\n");
                byteCommand.append(argument).append("\r\n");
            }
            os.write(byteCommand.toString().getBytes(StandardCharsets.UTF_8));
        }else{
            throw new IllegalArgumentException("command cannot be null or empty");
        }
    }
}
