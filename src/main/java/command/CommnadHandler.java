package command;

import protocol.response.Response;
import storage.ExpireResult;
import storage.KeyValueStore;
import storage.StoredValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CommnadHandler {
    private final KeyValueStore kvs ;

    public CommnadHandler(KeyValueStore kvs ) {
        this.kvs = kvs;
    }

    public Response handle(List<String> Pieces)  {
        if (Pieces == null || Pieces.isEmpty()) {
            return new Response.Error("ERR empty command");
        }
        return switch (Pieces.getFirst().toUpperCase(Locale.ROOT)){
            case "SET"  -> handleSet(Pieces);
            case "GET"  -> handleGet(Pieces);
            case "DEL"  -> handleDelete(Pieces);
            case "INCR" -> handleIncr(Pieces);
            case "DECR" -> handleDecr(Pieces);
            case "SETNX"-> handleSetnx(Pieces);
            case "EXPIRE" -> handleExpire(Pieces);
            case "TTL" -> handleTtl(Pieces);
            case "PEXPIREAT" -> handlePexpireat(Pieces);
            default -> new Response.Error("ERR unknown command") ;
        };
    }

    private Response handleSet(List<String> Pieces)  {
            if(Pieces.size()==3){
                kvs.set(Pieces.get(1), Pieces.get(2));
                return new Response.SimpleString("OK");
            }else if(Pieces.size()==5){
                if(Pieces.get(3).equalsIgnoreCase("PXAT")){
                    try {
                        long deadlineMillis = Long.parseLong(Pieces.get(4));
                        kvs.setAt(Pieces.get(1), Pieces.get(2), deadlineMillis);

                        return new Response.SimpleString("OK");

                    } catch (IllegalArgumentException e) {
                        return new Response.Error("ERR invalid expiration timestamp");
                    }
                }else{
                    return new Response.Error("ERR syntax error");
                }
            }
            return new Response.Error("ERR wrong number of arguments");
    }

    private Response handleGet(List<String> Pieces){
            if(Pieces.size()==2){
                String result =  kvs.get(Pieces.get(1));
                return result==null?new Response.NullValue(): new Response.BulkString(result);
            }
            return new Response.Error("ERR wrong number of arguments");
    }

    private Response handleDelete(List<String> Pieces){
            if(Pieces.size()==2){
                return new Response.LongValue(kvs.del(Pieces.get(1))?1:0);
            }
        return new Response.Error("ERR wrong number of arguments");

    }

    private Response handleIncr(List<String> args){
        return handleIncrementWithMetadata(args).response();
    }

    private Response handleDecr(List<String> args){
        return handleDecrementWithMetadata(args).response();
    }

    private Response handleSetnx(List<String> Pieces){
        if(Pieces.size()==3){
            return new Response.LongValue( kvs.setnx(Pieces.get(1), Pieces.get(2))?1:0);
        }
        return new Response.Error("ERR wrong number of arguments");

    }
    private Response handleExpire(List<String> Pieces){
       return handleExpireWithMetadata(Pieces).response();
    }

    private Response handleTtl(List<String> Pieces){
        if(Pieces.size()==2){
            return new Response.LongValue(kvs.ttl(Pieces.get(1)));
        }
        return new Response.Error("ERR wrong number of arguments");
    }

    private Response handlePexpireat(List<String> args ) {
        return handleexpireatWithMetadata(args).response();
    }

    private CommandExecutionResult handleExpireWithMetadata(List<String> args ) {
        try {
            if (args.size()==3){
                long seconds =  Long.parseLong(args.get(2));
                ExpireResult result = kvs.expireWithDeadline((args.get(1)),seconds);
                Response.LongValue response = new Response.LongValue(result.success()?1:0);
                List<List<String>> aofCommand = List.of();
                if(result.success()){
                    aofCommand = List.of(
                            List.of(
                                    "PEXPIREAT",
                                    args.get(1),
                                    Long.toString(result.deadLineMills())
                            )
                    );
                }
                return new CommandExecutionResult(response, aofCommand);
            }

        }catch (NumberFormatException | ArithmeticException e){
            Response.Error error = new Response.Error("ERR value is not an integer or out of range");
            return new CommandExecutionResult(error,List.of());
        }catch(IllegalArgumentException e){
            Response.Error error = new Response.Error("ERR invalid expiration deadline");
            return new CommandExecutionResult(error,List.of());
        }
        Response.Error error = new Response.Error("ERR wrong number of arguments");
        return new CommandExecutionResult(error, List.of());
    }

    public CommandExecutionResult handleWithPersistence(List<String> args){
        if ( args==null || args.isEmpty()){
            return new CommandExecutionResult(new Response.Error("ERR empty command"),List.of());
        }
        if(args.getFirst().equalsIgnoreCase("EXPIRE")){
            return handleExpireWithMetadata(args);
        }else if(args.getFirst().equalsIgnoreCase("INCR")){
            return handleIncrementWithMetadata(args);
        }else if (args.getFirst().equalsIgnoreCase("DECR")) {
            return handleDecrementWithMetadata(args);
        }else if (args.getFirst().equalsIgnoreCase("PEXPIREAT")) {
            return handleexpireatWithMetadata(args);
        }else{
            Response respone = handle(args);
            return new CommandExecutionResult(respone,List.of());
        }
    }
    private CommandExecutionResult handleIncrementWithMetadata(List<String> args ) {
        try{
            if(args.size()==2){
                String key = args.get(1);
                StoredValue result = kvs.incrementWithState(key);
                Response.LongValue response =
                        new Response.LongValue(Long.parseLong(result.value()));

                List<String> aofCommand;

                if (result.expiresAt() == Long.MAX_VALUE) {
                    aofCommand = List.of(
                            "SET",
                            key,
                            result.value()
                    );
                } else {
                    aofCommand = List.of(
                            "SET",
                            key,
                            result.value(),
                            "PXAT",
                            Long.toString(result.expiresAt())
                    );
                }

                return new CommandExecutionResult(
                        response,
                        List.of(aofCommand)
                );
            }
        }catch (NumberFormatException e) {
            return new CommandExecutionResult(
                    new Response.Error("ERR value is not an integer"),
                    List.of()
            );
        } catch (ArithmeticException e) {
            return new CommandExecutionResult(
                    new Response.Error("ERR value is not an integer or out of range"),
                    List.of()
            );
        }
        return new CommandExecutionResult(new Response.Error("ERR wrong number of arguments"),List.of());
    }

    private CommandExecutionResult handleDecrementWithMetadata(List<String> args ) {
            try{
                if(args.size()==2){
                    String key = args.get(1);
                    StoredValue result = kvs.decrementWithState(key);
                    Response.LongValue response =
                            new Response.LongValue(Long.parseLong(result.value()));

                    List<String> aofCommand;

                    if (result.expiresAt() == Long.MAX_VALUE) {
                        aofCommand = List.of(
                                "SET",
                                key,
                                result.value()
                        );
                    } else {
                        aofCommand = List.of(
                                "SET",
                                key,
                                result.value(),
                                "PXAT",
                                Long.toString(result.expiresAt())
                        );
                    }

                    return new CommandExecutionResult(
                            response,
                            List.of(aofCommand)
                    );
                }
            }catch (NumberFormatException e) {
                return new CommandExecutionResult(
                        new Response.Error("ERR value is not an integer"),
                        List.of()
                );
            } catch (ArithmeticException e) {
                return new CommandExecutionResult(
                        new Response.Error("ERR value is not an integer or out of range"),
                        List.of()
                );
            }
            return new CommandExecutionResult(new Response.Error("ERR wrong number of arguments"),List.of());
    }

    private CommandExecutionResult handleexpireatWithMetadata(List<String> args ) {
        try{
            if(args.size()==3){
                boolean success = kvs.expireAt(
                        args.get(1),
                        Long.parseLong(args.getLast())
                );

                Response response = new Response.LongValue(success ? 1 : 0);

                List<List<String>> aofCommands =
                        success ? List.of(List.copyOf(args)) : List.of();

                return new CommandExecutionResult(response, aofCommands);
            }
        }catch ( IllegalArgumentException e){
            Response.Error error = new Response.Error("ERR value is not an integer or out of range");
            return new CommandExecutionResult(error,List.of());
        }
        return new CommandExecutionResult(new Response.Error("ERR wrong number of arguments"),List.of());
    }

}
