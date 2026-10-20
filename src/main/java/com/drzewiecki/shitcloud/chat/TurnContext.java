package com.drzewiecki.shitcloud.chat;

/**
 * Who is asking, in which conversation; handed to every tool call.
 */
public record TurnContext(String caller, Conversation conversation) {
}
