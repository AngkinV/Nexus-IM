package com.nexus.chat.controller;

import com.nexus.chat.dto.ChatDTO;
import com.nexus.chat.dto.CreateGroupRequest;
import com.nexus.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/direct")
    public ResponseEntity<ChatDTO> createDirectChat(
            @RequestParam Long userId,
            @RequestParam Long contactId) {
        log.debug("创建私聊: userId={}, contactId={}", userId, contactId);
        try {
            ChatDTO chat = chatService.createDirectChat(userId, contactId);
            log.info("私聊创建成功: chatId={}, userId={}, contactId={}", chat.getId(), userId, contactId);
            return ResponseEntity.ok(chat);
        } catch (RuntimeException e) {
            log.warn("创建私聊失败: userId={}, contactId={}, reason={}", userId, contactId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/group")
    public ResponseEntity<ChatDTO> createGroupChat(
            @RequestParam Long userId,
            @RequestBody CreateGroupRequest request) {
        log.debug("创建群聊: userId={}, name={}", userId, request.getName());
        try {
            ChatDTO chat = chatService.createGroupChat(userId, request);
            log.info("群聊创建成功: chatId={}, name={}", chat.getId(), chat.getName());
            return ResponseEntity.ok(chat);
        } catch (RuntimeException e) {
            log.warn("创建群聊失败: userId={}, reason={}", userId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ChatDTO>> getUserChats(@PathVariable Long userId) {
        log.debug("获取用户聊天列表: userId={}", userId);
        List<ChatDTO> chats = chatService.getUserChats(userId);
        return ResponseEntity.ok(chats);
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<ChatDTO> getChatById(
            @PathVariable Long chatId,
            @RequestParam Long userId) {
        log.debug("获取聊天详情: chatId={}, userId={}", chatId, userId);
        try {
            ChatDTO chat = chatService.getChatById(chatId, userId);
            return ResponseEntity.ok(chat);
        } catch (RuntimeException e) {
            log.warn("获取聊天详情失败: chatId={}, userId={}, reason={}", chatId, userId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

}
