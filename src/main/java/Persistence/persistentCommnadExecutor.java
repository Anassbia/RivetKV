package Persistence;

import command.CommandExecutionResult;
import command.CommnadHandler;
import protocol.response.Response;

import java.io.IOException;
import java.util.List;

public class persistentCommnadExecutor {
    private final CommnadHandler  handler ;
    private final AofWriter writer;


    public persistentCommnadExecutor(CommnadHandler handler, AofWriter writer) {
        this.handler = handler;
        this.writer = writer;
    }

    public synchronized Response execute(List<String> command) throws IOException {
        CommandExecutionResult result = handler.handleWithPersistence(command);
        Response response  = result.response();
        if(response instanceof Response.Error) {
            return response;
        }
        String first = command.getFirst();
        if(first.equalsIgnoreCase("SET")) {
           writer.append(command);
        }
        if(first.equalsIgnoreCase("DEL")&& (response instanceof Response.LongValue(long value)) )  {
            if(value == 1){
                writer.append(command);
            }
        }
       for(List<String> aofCommand:result.aofCommand()){
           writer.append(aofCommand);
       }
        if(first.equalsIgnoreCase("SETNX") && (response instanceof Response.LongValue(long value)) )  {
            if(value == 1){
                writer.append(List.of("SET", command.get(1), command.get(2)));
            }
        }

        return response;
    }
}
