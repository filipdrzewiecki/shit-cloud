package com.drzewiecki.shitcloud.chat;

/**
 * Who is asking, in which conversation; handed to every tool call.
 *
 * @param data the conversation's latest data parts; the engine fills it in before running a tool
 */
public record TurnContext(String caller, Conversation conversation, DataParts data) {

    public TurnContext(String caller, Conversation conversation) {
        this(caller, conversation, DataParts.NONE);
    }

    public TurnContext with(DataParts data) {
        return new TurnContext(caller, conversation, data);
    }
}
