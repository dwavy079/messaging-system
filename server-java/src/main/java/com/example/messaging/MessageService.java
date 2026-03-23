package com.example.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class MessageService {
    private final Map<String, List<ChatMessage>> roomMessages = new ConcurrentHashMap<>();
    private final NativeCodecBridge codecBridge;

    public MessageService(NativeCodecBridge codecBridge) {
        this.codecBridge = codecBridge;
    }

    public ChatMessage addMessage(SendMessageRequest request) {
        String encoded = codecBridge.encode(request.content());
        ChatMessage stored = new ChatMessage(
                UUID.randomUUID().toString(),
                request.roomId(),
                request.sender(),
                encoded,
                Instant.now().toEpochMilli()
        );
        roomMessages.computeIfAbsent(request.roomId(), key -> new ArrayList<>()).add(stored);
        return decodeForClient(stored);
    }

    public List<ChatMessage> getHistory(String roomId) {
        List<ChatMessage> list = roomMessages.getOrDefault(roomId, List.of());
        return list.stream().map(this::decodeForClient).toList();
    }

    private ChatMessage decodeForClient(ChatMessage stored) {
        return new ChatMessage(
                stored.id(),
                stored.roomId(),
                stored.sender(),
                codecBridge.decode(stored.content()),
                stored.timestamp()
        );
    }
}
