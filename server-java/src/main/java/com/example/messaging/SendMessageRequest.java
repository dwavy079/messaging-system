package com.example.messaging;

public record SendMessageRequest(
        String roomId,
        String sender,
        String content
) {
}
