package command;

import protocol.response.Response;

import java.util.List;

public record CommandExecutionResult(
        Response response ,
        List<List<String>> aofCommand
) {
}
