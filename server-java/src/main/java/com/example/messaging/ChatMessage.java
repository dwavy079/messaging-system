package com.example.messaging;

public record ChatMessage(
        String id,
        String roomId,
        String sender,
        String content,
        long timestamp
) {
}
