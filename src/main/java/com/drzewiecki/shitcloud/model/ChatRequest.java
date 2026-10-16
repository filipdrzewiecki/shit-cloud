package com.drzewiecki.shitcloud.model;

import java.util.List;

public record ChatRequest(List<ChatMessage> messages) {
}
