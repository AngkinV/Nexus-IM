package com.nexus.chat.service;

import com.nexus.chat.dto.ChatDTO;
import com.nexus.chat.dto.CreateGroupRequest;
import com.nexus.chat.dto.MessageDTO;
import com.nexus.chat.dto.UserDTO;
import com.nexus.chat.dto.WebSocketMessage;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.Chat;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.Message;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public ChatDTO createDirectChat(Long userId, Long contactId) {
        // Use the existing repository query instead of manual iteration
        Optional<Chat> existingChat = chatRepository.findDirectChatBetweenUsers(userId, contactId);
        if (existingChat.isPresent()) {
            return mapToDTO(existingChat.get(), userId);
        }

        // Create new direct chat
        Chat chat = new Chat();
        chat.setType(Chat.ChatType.direct);
        chat.setCreatedBy(userId);
        Chat savedChat = chatRepository.save(chat);

        // Add both users as members
        ChatMember member1 = new ChatMember();
        member1.setChatId(savedChat.getId());
        member1.setUserId(userId);
        member1.setIsAdmin(false);
        chatMemberRepository.save(member1);

        ChatMember member2 = new ChatMember();
        member2.setChatId(savedChat.getId());
        member2.setUserId(contactId);
        member2.setIsAdmin(false);
        chatMemberRepository.save(member2);

        ChatDTO chatDTO = mapToDTO(savedChat, userId);

        // Notify the contact about the new chat via WebSocket
        ChatDTO contactChatDTO = mapToDTO(savedChat, contactId);
        WebSocketMessage wsMessage = new WebSocketMessage(
                WebSocketMessage.MessageType.CHAT_CREATED,
                contactChatDTO);
        messagingTemplate.convertAndSendToUser(String.valueOf(contactId), "/queue/chats", wsMessage);

        return chatDTO;
    }

    @Transactional
    public ChatDTO createGroupChat(Long userId, CreateGroupRequest request) {
        // Validation
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("Group name is required");
        }
        if (request.getName().length() > 100) {
            throw new BusinessException("Group name must be less than 100 characters");
        }
        if (request.getDescription() != null && request.getDescription().length() > 200) {
            throw new BusinessException("Group description must be less than 200 characters");
        }
        if (request.getMemberIds() == null || request.getMemberIds().isEmpty()) {
            throw new BusinessException("At least one member is required");
        }
        if (request.getMemberIds().size() > 200) {
            throw new BusinessException("Group cannot have more than 200 members");
        }

        // Batch verify all member IDs exist
        List<Long> otherMemberIds = request.getMemberIds().stream()
                .filter(id -> !id.equals(userId))
                .collect(Collectors.toList());
        if (!otherMemberIds.isEmpty()) {
            List<User> existingUsers = userRepository.findAllByIdIn(otherMemberIds);
            if (existingUsers.size() != otherMemberIds.size()) {
                throw new BusinessException("One or more members not found");
            }
        }

        // Create group chat
        Chat chat = new Chat();
        chat.setType(Chat.ChatType.group);
        chat.setName(request.getName().trim());
        chat.setDescription(request.getDescription());
        chat.setAvatarUrl(request.getAvatar());
        chat.setIsPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : false);
        chat.setCreatedBy(userId);

        int memberCount = 1 + otherMemberIds.size();
        chat.setMemberCount(memberCount);

        Chat savedChat = chatRepository.save(chat);

        // Add creator as admin member
        ChatMember creatorMember = new ChatMember();
        creatorMember.setChatId(savedChat.getId());
        creatorMember.setUserId(userId);
        creatorMember.setIsAdmin(true);
        chatMemberRepository.save(creatorMember);

        // Add other members
        for (Long memberId : otherMemberIds) {
            ChatMember member = new ChatMember();
            member.setChatId(savedChat.getId());
            member.setUserId(memberId);
            member.setIsAdmin(false);
            chatMemberRepository.save(member);
        }

        ChatDTO chatDTO = mapToDTO(savedChat, userId);

        // Notify all members about the new group via WebSocket
        for (Long memberId : otherMemberIds) {
            ChatDTO memberChatDTO = mapToDTO(savedChat, memberId);
            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.CHAT_CREATED,
                    memberChatDTO);
            messagingTemplate.convertAndSendToUser(String.valueOf(memberId), "/queue/chats", wsMessage);
        }

        return chatDTO;
    }

    /**
     * Optimized: Batch-loads all data in 4 queries instead of ~120.
     * Before: For 20 chats x 5 members = ~120 queries (N+1 problem)
     * After: 4 queries total (chats, members, users, lastMessages)
     */
    public List<ChatDTO> getUserChats(Long userId) {
        // Query 1: Get all chats for the user
        List<Chat> chats = chatRepository.findByUserIdOrderByLastMessageAtDesc(userId);
        if (chats.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> chatIds = chats.stream().map(Chat::getId).collect(Collectors.toList());

        // Query 2: Batch load all members for all chats
        List<ChatMember> allMembers = chatMemberRepository.findByChatIdIn(chatIds);
        Map<Long, List<ChatMember>> membersByChatId = allMembers.stream()
                .collect(Collectors.groupingBy(ChatMember::getChatId));

        // Query 3: Batch load all user details for all members
        Set<Long> allUserIds = allMembers.stream()
                .map(ChatMember::getUserId)
                .collect(Collectors.toSet());
        List<User> allUsers = userRepository.findAllByIdIn(allUserIds);
        Map<Long, User> usersById = allUsers.stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        // Query 4: Batch load last messages for all chats
        List<Message> lastMessages = messageRepository.findLastMessagesByChatIds(chatIds);
        Map<Long, Message> lastMessageByChatId = lastMessages.stream()
                .collect(Collectors.toMap(Message::getChatId, Function.identity()));

        // Build unread count map from the members we already loaded
        Map<Long, Integer> unreadCountByChatId = new HashMap<>();
        for (ChatMember cm : allMembers) {
            if (cm.getUserId().equals(userId)) {
                unreadCountByChatId.put(cm.getChatId(), cm.getUnreadCount());
            }
        }

        // Assemble DTOs in memory (no more queries)
        return chats.stream().map(chat -> {
            ChatDTO dto = new ChatDTO();
            dto.setId(chat.getId());
            dto.setType(chat.getType());
            dto.setName(chat.getName());
            dto.setDescription(chat.getDescription());
            dto.setAvatar(chat.getAvatarUrl());
            dto.setIsPrivate(chat.getIsPrivate());
            dto.setCreatedBy(chat.getCreatedBy());
            dto.setMemberCount(chat.getMemberCount());
            dto.setCreatedAt(chat.getCreatedAt());
            dto.setLastMessageAt(chat.getLastMessageAt());

            // Build member DTOs from cache
            List<ChatMember> chatMembers = membersByChatId.getOrDefault(chat.getId(), Collections.emptyList());
            List<UserDTO> memberDTOs = chatMembers.stream()
                    .map(cm -> {
                        User user = usersById.get(cm.getUserId());
                        if (user != null) {
                            return new UserDTO(
                                    user.getId(),
                                    user.getUsername(),
                                    user.getNickname(),
                                    user.getAvatarUrl(),
                                    user.getIsOnline(),
                                    user.getLastSeen());
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            dto.setMembers(memberDTOs);

            // For direct chat, set name as the other user's nickname
            if (chat.getType() == Chat.ChatType.direct && dto.getName() == null) {
                memberDTOs.stream()
                        .filter(u -> !u.getId().equals(userId))
                        .findFirst()
                        .ifPresent(user -> dto.setName(user.getNickname()));
            }

            // Set last message from cache
            Message lastMsg = lastMessageByChatId.get(chat.getId());
            if (lastMsg != null) {
                User sender = usersById.get(lastMsg.getSenderId());
                if (sender != null) {
                    MessageDTO msgDTO = new MessageDTO();
                    msgDTO.setId(lastMsg.getId());
                    msgDTO.setChatId(lastMsg.getChatId());
                    msgDTO.setSenderId(lastMsg.getSenderId());
                    msgDTO.setSenderNickname(sender.getNickname());
                    msgDTO.setSenderAvatar(sender.getAvatarUrl());
                    msgDTO.setContent(lastMsg.getContent());
                    msgDTO.setMessageType(lastMsg.getMessageType());
                    msgDTO.setFileUrl(lastMsg.getFileUrl());
                    msgDTO.setCreatedAt(lastMsg.getCreatedAt());
                    dto.setLastMessage(msgDTO);
                }
            }

            // Set unread count from cache
            dto.setUnreadCount(unreadCountByChatId.getOrDefault(chat.getId(), 0));

            return dto;
        }).collect(Collectors.toList());
    }

    public ChatDTO getChatById(Long chatId, Long userId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new BusinessException("error.chat.not.found"));

        // Verify user is a member
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
            throw new BusinessException("error.chat.not.member");
        }

        return mapToDTO(chat, userId);
    }

    /**
     * Single-chat DTO mapping (used for individual chat lookups, not list).
     * For list operations, use getUserChats() which does batch loading.
     */
    private ChatDTO mapToDTO(Chat chat, Long currentUserId) {
        ChatDTO dto = new ChatDTO();
        dto.setId(chat.getId());
        dto.setType(chat.getType());
        dto.setName(chat.getName());
        dto.setDescription(chat.getDescription());
        dto.setAvatar(chat.getAvatarUrl());
        dto.setIsPrivate(chat.getIsPrivate());
        dto.setCreatedBy(chat.getCreatedBy());
        dto.setMemberCount(chat.getMemberCount());
        dto.setCreatedAt(chat.getCreatedAt());
        dto.setLastMessageAt(chat.getLastMessageAt());

        // Get chat members
        List<ChatMember> members = chatMemberRepository.findByChatId(chat.getId());
        Set<Long> memberUserIds = members.stream().map(ChatMember::getUserId).collect(Collectors.toSet());
        List<User> users = userRepository.findAllByIdIn(memberUserIds);
        Map<Long, User> usersById = users.stream().collect(Collectors.toMap(User::getId, Function.identity()));

        List<UserDTO> memberDTOs = members.stream()
                .map(member -> {
                    User user = usersById.get(member.getUserId());
                    if (user != null) {
                        return new UserDTO(
                                user.getId(),
                                user.getUsername(),
                                user.getNickname(),
                                user.getAvatarUrl(),
                                user.getIsOnline(),
                                user.getLastSeen());
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        dto.setMembers(memberDTOs);

        // For direct chat, set name as the other user's nickname
        if (chat.getType() == Chat.ChatType.direct && dto.getName() == null) {
            memberDTOs.stream()
                    .filter(u -> !u.getId().equals(currentUserId))
                    .findFirst()
                    .ifPresent(user -> dto.setName(user.getNickname()));
        }

        // Get only the last message (was loading ALL messages before)
        messageRepository.findFirstByChatIdOrderByCreatedAtDesc(chat.getId())
                .ifPresent(lastMsg -> {
                    User sender = usersById.get(lastMsg.getSenderId());
                    // If sender not in members (e.g., left group), fetch individually
                    if (sender == null) {
                        sender = userRepository.findById(lastMsg.getSenderId()).orElse(null);
                    }
                    if (sender != null) {
                        MessageDTO msgDTO = new MessageDTO();
                        msgDTO.setId(lastMsg.getId());
                        msgDTO.setChatId(lastMsg.getChatId());
                        msgDTO.setSenderId(lastMsg.getSenderId());
                        msgDTO.setSenderNickname(sender.getNickname());
                        msgDTO.setSenderAvatar(sender.getAvatarUrl());
                        msgDTO.setContent(lastMsg.getContent());
                        msgDTO.setMessageType(lastMsg.getMessageType());
                        msgDTO.setFileUrl(lastMsg.getFileUrl());
                        msgDTO.setCreatedAt(lastMsg.getCreatedAt());
                        dto.setLastMessage(msgDTO);
                    }
                });

        // Get unread count for current user
        members.stream()
                .filter(m -> m.getUserId().equals(currentUserId))
                .findFirst()
                .ifPresent(member -> dto.setUnreadCount(member.getUnreadCount()));

        return dto;
    }

}
