package com.drzewiecki.shitcloud.model;

import java.time.Instant;

public record GuestToken(String token, Instant expiresAt) {
}
