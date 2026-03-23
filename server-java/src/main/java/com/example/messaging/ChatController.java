package com.example.messaging;

import java.util.List;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
public class ChatController {
    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(MessageService messageService, SimpMessagingTemplate messagingTemplate) {
        this.messageService = messageService;
        this.messagingTemplate = messagingTemplate;
    }

    @GetMapping("/{roomId}")
    public List<ChatMessage> history(@PathVariable String roomId) {
        return messageService.getHistory(roomId);
    }

    @PostMapping
    public ChatMessage post(@RequestBody SendMessageRequest request) {
        ChatMessage message = messageService.addMessage(request);
        messagingTemplate.convertAndSend("/topic/rooms/" + message.roomId(), message);
        return message;
    }

    @MessageMapping("/send")
    public void send(@Payload SendMessageRequest request) {
        ChatMessage message = messageService.addMessage(request);
        messagingTemplate.convertAndSend("/topic/rooms/" + message.roomId(), message);
    }
}
