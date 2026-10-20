package com.drzewiecki.shitcloud.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param maxTokens       output limit of one model call
 * @param maxSteps        model calls in one answer; each tool round starts a new one
 * @param historyMessages messages of the conversation resent to the model
 * @param pingInterval    how often a quiet stream gets a comment line
 */
@ConfigurationProperties("chat")
public record ChatSettings(int maxTokens, int maxSteps, int historyMessages, Duration pingInterval) {
}
