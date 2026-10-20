package com.drzewiecki.shitcloud.chat;

import java.util.List;

/**
 * @param output   the tool's result as the user sees it, and as the model reads it unless {@code forModel} is set
 * @param forModel what the model reads instead of {@code output} when the two differ — e.g. a whole file the user
 *                 needs no copy of; not saved with the conversation
 * @param data     parts for the client ({@code data-<name>}), e.g. a draft for the preview
 */
public record ToolResult(Object output, String forModel, List<DataPart> data) {

    public static ToolResult of(Object output, DataPart... data) {
        return new ToolResult(output, null, List.of(data));
    }

    public static ToolResult withModelText(Object output, String forModel) {
        return new ToolResult(output, forModel, List.of());
    }

    /**
     * @param id a fixed id makes a later part replace the earlier one on the client
     */
    public record DataPart(String name, String id, Object data) {
    }
}
