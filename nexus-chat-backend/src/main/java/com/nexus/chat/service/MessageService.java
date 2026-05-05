package com.nexus.chat.service;

import com.nexus.chat.dto.MessageDTO;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.FileUpload;
import com.nexus.chat.model.Message;
import com.nexus.chat.model.MessageReadStatus;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.FileUploadRepository;
import com.nexus.chat.repository.MessageReadStatusRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageReadStatusRepository messageReadStatusRepository;
    private final FileUploadRepository fileUploadRepository;
    private final MessageSequenceService messageSequenceService;

    /**
     * Send a message with sequence number and client message ID for deduplication.
     * Phase 3: Full reliability support.
     */
    @Transactional
    public MessageDTO sendMessage(Long chatId, Long senderId, String content, Message.MessageType messageType,
            String fileUrl, String clientMsgId) {
        // Verify sender is a member
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, senderId)) {
            throw new BusinessException("error.chat.not.member");
        }

        // Deduplication: check if message with this clientMsgId already exists
        if (clientMsgId != null && !clientMsgId.isEmpty()) {
            if (messageRepository.existsByClientMessageId(clientMsgId)) {
                log.warn("重复消息被拒绝: clientMsgId={}", clientMsgId);
                throw new BusinessException("error.message.duplicate");
            }
        }

        // Generate sequence number atomically
        long sequenceNumber = messageSequenceService.nextSequenceNumber(chatId);

        // Create message
        Message message = new Message();
        message.setChatId(chatId);
        message.setSenderId(senderId);
        message.setContent(content);
        message.setMessageType(messageType);
        message.setFileUrl(fileUrl);
        message.setClientMessageId(clientMsgId);
        message.setSequenceNumber(sequenceNumber);

        Message savedMessage = messageRepository.save(message);

        // Create read status for all chat members except sender
        List<ChatMember> members = chatMemberRepository.findByChatId(chatId);
        for (ChatMember member : members) {
            if (!member.getUserId().equals(senderId)) {
                // Create read status
                MessageReadStatus readStatus = new MessageReadStatus();
                readStatus.setMessageId(savedMessage.getId());
                readStatus.setUserId(member.getUserId());
                readStatus.setIsRead(false);
                messageReadStatusRepository.save(readStatus);
            }
        }

        // Batch increment unread count for all members except sender (1 query)
        chatMemberRepository.incrementUnreadForOthers(chatId, senderId);

        return mapToDTO(savedMessage);
    }

    public List<MessageDTO> getChatMessages(Long chatId, Long userId, int page, int size) {
        // Verify user is a member
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
            throw new BusinessException("error.chat.not.member");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        Page<Message> messages = messageRepository.findByChatId(chatId, pageable);

        return messages.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public void markMessageAsRead(Long messageId, Long userId) {
        MessageReadStatus readStatus = messageReadStatusRepository
                .findByMessageIdAndUserId(messageId, userId)
                .orElseThrow(() -> new RuntimeException("Read status not found"));

        if (!readStatus.getIsRead()) {
            readStatus.setIsRead(true);
            readStatus.setReadAt(LocalDateTime.now());
            messageReadStatusRepository.save(readStatus);

            // Decrement unread count
            Message message = messageRepository.findById(messageId).orElse(null);
            if (message != null) {
                ChatMember member = chatMemberRepository
                        .findByChatIdAndUserId(message.getChatId(), userId)
                        .orElse(null);
                if (member != null && member.getUnreadCount() > 0) {
                    member.setUnreadCount(member.getUnreadCount() - 1);
                    chatMemberRepository.save(member);
                }
            }
        }
    }

    @Transactional
    public void markChatMessagesAsRead(Long chatId, Long userId) {
        // Bulk mark all unread messages as read in 1 query (was 2000+ queries for 1000 messages)
        messageReadStatusRepository.bulkMarkAsRead(chatId, userId, LocalDateTime.now());

        // Reset unread count in 1 query (was find + set + save)
        chatMemberRepository.resetUnreadCount(chatId, userId);
    }

    private MessageDTO mapToDTO(Message message) {
        User sender = userRepository.findById(message.getSenderId()).orElse(null);

        MessageDTO dto = new MessageDTO();
        dto.setId(message.getId());
        dto.setChatId(message.getChatId());
        dto.setSenderId(message.getSenderId());
        dto.setContent(message.getContent());
        dto.setMessageType(message.getMessageType());
        dto.setFileUrl(message.getFileUrl());
        dto.setCreatedAt(message.getCreatedAt());
        dto.setSequenceNumber(message.getSequenceNumber());
        dto.setClientMsgId(message.getClientMessageId());

        if (sender != null) {
            dto.setSenderNickname(sender.getNickname());
            dto.setSenderAvatar(sender.getAvatarUrl());
        }

        // 如果是文件消息，填充文件详情
        if ((message.getMessageType() == Message.MessageType.file ||
             message.getMessageType() == Message.MessageType.image) &&
            message.getFileUrl() != null) {

            // 尝试从 fileUrl 中提取 fileId
            String fileUrl = message.getFileUrl();
            String fileId = null;

            // fileUrl 格式可能是 /uploads/2024/01/01/uuid.ext 或 /api/files/download/uuid
            if (fileUrl.contains("/api/files/download/")) {
                fileId = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
            } else if (fileUrl.contains("/uploads/")) {
                // 从路径中提取文件名（不含扩展名）作为fileId
                String filename = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
                if (filename.contains(".")) {
                    fileId = filename.substring(0, filename.lastIndexOf("."));
                }
            }

            if (fileId != null) {
                fileUploadRepository.findByFileId(fileId).ifPresent(fileUpload -> {
                    dto.setFileId(fileUpload.getFileId());
                    dto.setFileName(fileUpload.getOriginalName());
                    dto.setFileSize(fileUpload.getFileSize());
                    dto.setMimeType(fileUpload.getMimeType());
                    dto.setDownloadUrl("/api/files/download/" + fileUpload.getFileId());
                    dto.setPreviewUrl("/api/files/preview/" + fileUpload.getFileId());
                });
            }
        }

        return dto;
    }

}
