package com.drzewiecki.shitcloud.chat;

import java.util.Map;

/**
 * A tool the model may call. The engine shows the call and its result in the stream, so {@link ToolResult#output()}
 * must stay small (a summary, not a dataset); anything big for the client goes into a data part.
 */
public interface ChatTool {

    String name();

    /**
     * Tells the model when and how to use the tool.
     */
    String description();

    /**
     * JSON Schema of the input object.
     */
    String inputSchema();

    /**
     * Throw {@link ToolException} for a failure the model should read and react to; anything else is reported as
     * a generic tool failure.
     */
    ToolResult run(Map<String, Object> input, TurnContext context);

    /**
     * The input as saved with the conversation and resent to the model later. Override to drop what is already
     * kept elsewhere (e.g. a whole HTML file that also travels as a data part).
     */
    default Map<String, Object> inputForHistory(Map<String, Object> input) {
        return input;
    }

    class ToolException extends RuntimeException {

        public ToolException(String message) {
            super(message);
        }
    }
}
