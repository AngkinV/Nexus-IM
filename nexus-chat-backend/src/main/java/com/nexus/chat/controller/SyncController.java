package com.nexus.chat.controller;

import com.nexus.chat.dto.*;
import com.nexus.chat.model.Chat;
import com.nexus.chat.model.Contact;
import com.nexus.chat.model.Message;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.ContactRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Delta synchronization endpoint.
 * Returns only data that changed since the client's last sync timestamp.
 */
@Slf4j
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;

    /**
     * GET /api/sync/delta?since={ISO timestamp}&types=messages,chats,contacts
     *
     * Returns delta data since the given timestamp for the authenticated user.
     */
    @GetMapping("/delta")
    public ResponseEntity<SyncResponseDTO> getDelta(
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "messages,chats,contacts") String types) {

        // Extract userId from JWT-authenticated SecurityContext
        Long userId = getAuthenticatedUserId();
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        SyncResponseDTO response = new SyncResponseDTO();
        Set<String> typeSet = new HashSet<>(Arrays.asList(types.split(",")));

        // Parse since timestamp
        LocalDateTime sinceTime = null;
        if (since != null && !since.isEmpty()) {
            try {
                sinceTime = LocalDateTime.parse(since.replace("Z", "").replace("+00:00", ""));
            } catch (DateTimeParseException e) {
                log.warn("[Sync] Invalid since timestamp: {}", since);
                return ResponseEntity.badRequest().build();
            }
        }

        // Get user's chat IDs
        List<ChatMember> memberships = chatMemberRepository.findByUserId(userId);
        List<Long> chatIds = memberships.stream()
                .map(ChatMember::getChatId)
                .collect(Collectors.toList());

        // Delta messages
        if (typeSet.contains("messages") && sinceTime != null && !chatIds.isEmpty()) {
            List<Message> deltaMessages = messageRepository.findByChatIdInAndCreatedAtAfter(chatIds, sinceTime);
            response.setMessages(deltaMessages.stream()
                    .map(this::toMessageDTO)
                    .collect(Collectors.toList()));
        } else {
            response.setMessages(Collections.emptyList());
        }

        // Delta chats (chats with new messages since last sync)
        if (typeSet.contains("chats") && sinceTime != null) {
            List<Chat> deltaChats = chatRepository.findByUserIdAndLastMessageAtAfter(userId, sinceTime);
            if (!deltaChats.isEmpty()) {
                response.setChats(deltaChats.stream()
                        .map(chat -> toChatDTO(chat, userId))
                        .collect(Collectors.toList()));
            } else {
                response.setChats(Collections.emptyList());
            }
        } else {
            response.setChats(Collections.emptyList());
        }

        // Delta contacts
        if (typeSet.contains("contacts")) {
            List<Contact> userContacts = contactRepository.findByUserId(userId);
            List<Long> contactUserIds = userContacts.stream()
                    .map(Contact::getContactUserId)
                    .collect(Collectors.toList());

            if (!contactUserIds.isEmpty()) {
                List<User> contactUsers = userRepository.findAllByIdIn(contactUserIds);
                Map<Long, User> userMap = contactUsers.stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

                response.setContacts(userContacts.stream()
                        .map(c -> toContactDTO(c, userMap.get(c.getContactUserId())))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));
            } else {
                response.setContacts(Collections.emptyList());
            }
        } else {
            response.setContacts(Collections.emptyList());
        }

        log.info("[Sync] Delta for user {}: {} messages, {} chats, {} contacts",
                userId,
                response.getMessages().size(),
                response.getChats().size(),
                response.getContacts().size());

        return ResponseEntity.ok(response);
    }

    private Long getAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            try {
                return Long.parseLong(auth.getPrincipal().toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private MessageDTO toMessageDTO(Message msg) {
        MessageDTO dto = new MessageDTO();
        dto.setId(msg.getId());
        dto.setChatId(msg.getChatId());
        dto.setSenderId(msg.getSenderId());
        dto.setContent(msg.getContent());
        dto.setMessageType(msg.getMessageType());
        dto.setFileUrl(msg.getFileUrl());
        dto.setCreatedAt(msg.getCreatedAt());
        dto.setSequenceNumber(msg.getSequenceNumber());
        dto.setClientMsgId(msg.getClientMessageId());
        return dto;
    }

    private ContactDTO toContactDTO(Contact contact, User user) {
        if (user == null) return null;
        ContactDTO dto = new ContactDTO();
        dto.setId(contact.getId());
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setNickname(user.getNickname());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setAddedAt(contact.getCreatedAt());
        return dto;
    }

    /**
     * Convert Chat entity to ChatDTO for delta sync.
     * Includes basic chat info, members, last message, and unread count.
     */
    private ChatDTO toChatDTO(Chat chat, Long currentUserId) {
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

        // Load members
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

        // Load last message
        messageRepository.findFirstByChatIdOrderByCreatedAtDesc(chat.getId())
                .ifPresent(lastMsg -> {
                    User sender = usersById.get(lastMsg.getSenderId());
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
                        msgDTO.setSequenceNumber(lastMsg.getSequenceNumber());
                        dto.setLastMessage(msgDTO);
                    }
                });

        // Set unread count for current user
        members.stream()
                .filter(m -> m.getUserId().equals(currentUserId))
                .findFirst()
                .ifPresent(member -> dto.setUnreadCount(member.getUnreadCount()));

        return dto;
    }
}
