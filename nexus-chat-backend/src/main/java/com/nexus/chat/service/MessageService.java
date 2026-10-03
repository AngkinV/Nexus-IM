package com.nexus.chat.service;

import com.nexus.chat.dto.MessageDTO;
import com.nexus.chat.dto.MessageEditHistoryDTO;
import com.nexus.chat.dto.MessageReactionDTO;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.FileUpload;
import com.nexus.chat.model.Message;
import com.nexus.chat.model.MessageDeliveryStatus;
import com.nexus.chat.model.MessageEditHistory;
import com.nexus.chat.model.MessageReaction;
import com.nexus.chat.model.MessageReadStatus;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.FileUploadRepository;
import com.nexus.chat.repository.MessageDeliveryStatusRepository;
import com.nexus.chat.repository.MessageEditHistoryRepository;
import com.nexus.chat.repository.MessageReactionRepository;
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
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private static final int EDIT_WINDOW_MINUTES = 15;
    private static final int RECALL_WINDOW_MINUTES = 2;
    private static final int MAX_EDIT_COUNT = 3;

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageReadStatusRepository messageReadStatusRepository;
    private final FileUploadRepository fileUploadRepository;
    private final MessageSequenceService messageSequenceService;
    private final MessageReactionRepository messageReactionRepository;
    private final MessageDeliveryStatusRepository messageDeliveryStatusRepository;
    private final MessageEditHistoryRepository messageEditHistoryRepository;

    /**
     * Send a message with sequence number and client message ID for deduplication.
     * Phase 3: Full reliability support.
     */
    @Transactional
    public MessageDTO sendMessage(Long chatId, Long senderId, String content, Message.MessageType messageType,
            String fileUrl, String clientMsgId) {
        return sendMessage(chatId, senderId, content, messageType, fileUrl, clientMsgId, null);
    }

    @Transactional
    public MessageDTO sendMessage(Long chatId, Long senderId, String content, Message.MessageType messageType,
            String fileUrl, String clientMsgId, Long replyToMessageId) {
        // Verify sender is a member
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, senderId)) {
            throw new BusinessException("error.chat.not.member");
        }

        if (replyToMessageId != null) {
            Message replied = messageRepository.findById(replyToMessageId)
                    .orElseThrow(() -> new BusinessException("error.message.reply.not_found"));
            if (!chatId.equals(replied.getChatId())) {
                throw new BusinessException("error.message.reply.cross_chat");
            }
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
        message.setReplyToMessageId(replyToMessageId);

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

                MessageDeliveryStatus deliveryStatus = new MessageDeliveryStatus();
                deliveryStatus.setMessageId(savedMessage.getId());
                deliveryStatus.setChatId(chatId);
                deliveryStatus.setUserId(member.getUserId());
                deliveryStatus.setState(MessageDeliveryStatus.DeliveryState.pending);
                messageDeliveryStatusRepository.save(deliveryStatus);
            }
        }

        // Batch increment unread count for all members except sender (1 query)
        chatMemberRepository.incrementUnreadForOthers(chatId, senderId);

        return mapToDTO(savedMessage, senderId);
    }

    public List<MessageDTO> getChatMessages(Long chatId, Long userId, int page, int size) {
        // Verify user is a member
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
            throw new BusinessException("error.chat.not.member");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        Page<Message> messages = messageRepository.findByChatId(chatId, pageable);

        return messages.stream()
                .map(message -> mapToDTO(message, userId))
                .collect(Collectors.toList());
    }

    public List<MessageDTO> searchMessages(Long chatId, Long userId, String query, int page, int size) {
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
            throw new BusinessException("error.chat.not.member");
        }
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.searchInChat(chatId, normalized, pageable)
                .stream()
                .map(message -> mapToDTO(message, userId))
                .collect(Collectors.toList());
    }

    public MessageDTO getMessageForUser(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException("error.message.not_found"));
        if (!chatMemberRepository.existsByChatIdAndUserId(message.getChatId(), userId)) {
            throw new BusinessException("error.chat.not.member");
        }
        return mapToDTO(message, userId);
    }

    @Transactional
    public MessageDTO editMessage(Long messageId, Long userId, String content) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException("error.message.not_found"));
        if (!message.getSenderId().equals(userId)) {
            throw new BusinessException("error.message.edit.forbidden");
        }
        if (Boolean.TRUE.equals(message.getIsRecalled())) {
            throw new BusinessException("error.message.recalled");
        }
        if (message.getMessageType() != Message.MessageType.text && message.getMessageType() != Message.MessageType.emoji) {
            throw new BusinessException("error.message.edit.unsupported_type");
        }
        if (!isWithinWindow(message.getCreatedAt(), EDIT_WINDOW_MINUTES)) {
            throw new BusinessException("error.message.edit.expired");
        }
        if (messageEditHistoryRepository.countByMessageId(message.getId()) >= MAX_EDIT_COUNT) {
            throw new BusinessException("error.message.edit.too_many");
        }
        if (content == null || content.equals(message.getContent())) {
            return mapToDTO(message, userId);
        }

        MessageEditHistory history = new MessageEditHistory();
        history.setMessageId(message.getId());
        history.setChatId(message.getChatId());
        history.setEditorUserId(userId);
        history.setPreviousContent(message.getContent());
        history.setNewContent(content);
        messageEditHistoryRepository.save(history);

        message.setContent(content);
        message.setIsEdited(true);
        message.setEditedAt(LocalDateTime.now());
        return mapToDTO(messageRepository.save(message), userId);
    }

    @Transactional
    public MessageDTO recallMessage(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException("error.message.not_found"));
        if (!message.getSenderId().equals(userId)) {
            throw new BusinessException("error.message.recall.forbidden");
        }
        if (!chatMemberRepository.existsByChatIdAndUserId(message.getChatId(), userId)) {
            throw new BusinessException("error.chat.not.member");
        }
        if (!isWithinWindow(message.getCreatedAt(), RECALL_WINDOW_MINUTES)) {
            throw new BusinessException("error.message.recall.expired");
        }
        message.setContent("");
        message.setFileUrl(null);
        message.setIsRecalled(true);
        message.setRecalledAt(LocalDateTime.now());
        return mapToDTO(messageRepository.save(message), userId);
    }

    @Transactional
    public List<MessageReactionDTO> toggleReaction(Long messageId, Long userId, String emoji) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException("error.message.not_found"));
        if (!chatMemberRepository.existsByChatIdAndUserId(message.getChatId(), userId)) {
            throw new BusinessException("error.chat.not.member");
        }
        String normalized = normalizeEmoji(emoji);
        messageReactionRepository.findByMessageIdAndUserIdAndEmoji(messageId, userId, normalized)
                .ifPresentOrElse(
                        messageReactionRepository::delete,
                        () -> {
                            MessageReaction reaction = new MessageReaction();
                            reaction.setMessageId(messageId);
                            reaction.setChatId(message.getChatId());
                            reaction.setUserId(userId);
                            reaction.setEmoji(normalized);
                            messageReactionRepository.save(reaction);
                        }
                );
        return reactionSummary(messageId, userId);
    }

    @Transactional
    public boolean markMessageDelivered(Long messageId, Long userId) {
        return messageDeliveryStatusRepository.markDelivered(messageId, userId, LocalDateTime.now()) > 0;
    }

    public List<MessageEditHistoryDTO> getEditHistory(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException("error.message.not_found"));
        if (!chatMemberRepository.existsByChatIdAndUserId(message.getChatId(), userId)) {
            throw new BusinessException("error.chat.not.member");
        }
        return messageEditHistoryRepository.findByMessageIdOrderByEditedAtAsc(messageId)
                .stream()
                .map(item -> new MessageEditHistoryDTO(
                        item.getId(),
                        item.getMessageId(),
                        item.getEditorUserId(),
                        item.getPreviousContent(),
                        item.getNewContent(),
                        item.getEditedAt()))
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

    private String normalizeEmoji(String emoji) {
        String normalized = emoji == null ? "" : emoji.trim();
        if (normalized.isEmpty() || normalized.length() > 16) {
            throw new BusinessException("error.message.reaction.invalid");
        }
        return normalized;
    }

    private boolean isWithinWindow(LocalDateTime createdAt, int minutes) {
        if (createdAt == null) return false;
        return !createdAt.plusMinutes(minutes).isBefore(LocalDateTime.now());
    }

    private List<MessageReactionDTO> reactionSummary(Long messageId, Long viewerUserId) {
        List<MessageReaction> own = viewerUserId == null
                ? List.of()
                : messageReactionRepository.findByMessageId(messageId).stream()
                    .filter(r -> viewerUserId.equals(r.getUserId()))
                    .toList();
        Set<String> ownEmoji = own.stream().map(MessageReaction::getEmoji).collect(Collectors.toSet());
        return messageReactionRepository.countByEmoji(messageId)
                .stream()
                .map(row -> new MessageReactionDTO(row.getEmoji(), row.getCount(), ownEmoji.contains(row.getEmoji())))
                .collect(Collectors.toList());
    }

    private MessageDTO mapToDTO(Message message) {
        return mapToDTO(message, null);
    }

    private MessageDTO mapToDTO(Message message, Long viewerUserId) {
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
        dto.setReplyToMessageId(message.getReplyToMessageId());
        dto.setIsEdited(Boolean.TRUE.equals(message.getIsEdited()));
        dto.setEditedAt(message.getEditedAt());
        int editHistoryCount = (int) messageEditHistoryRepository.countByMessageId(message.getId());
        dto.setEditCount(editHistoryCount);
        dto.setCanEdit(viewerUserId != null
                && viewerUserId.equals(message.getSenderId())
                && !Boolean.TRUE.equals(message.getIsRecalled())
                && (message.getMessageType() == Message.MessageType.text || message.getMessageType() == Message.MessageType.emoji)
                && isWithinWindow(message.getCreatedAt(), EDIT_WINDOW_MINUTES)
                && editHistoryCount < MAX_EDIT_COUNT);
        dto.setCanRecall(viewerUserId != null
                && viewerUserId.equals(message.getSenderId())
                && !Boolean.TRUE.equals(message.getIsRecalled())
                && isWithinWindow(message.getCreatedAt(), RECALL_WINDOW_MINUTES));
        dto.setIsRecalled(Boolean.TRUE.equals(message.getIsRecalled()));
        dto.setRecalledAt(message.getRecalledAt());
        dto.setReactions(reactionSummary(message.getId(), viewerUserId));
        dto.setDeliveredCount((int) messageDeliveryStatusRepository.findByMessageId(message.getId())
                .stream()
                .filter(s -> s.getState() == MessageDeliveryStatus.DeliveryState.delivered)
                .count());
        dto.setReadCount((int) messageReadStatusRepository.findByMessageId(message.getId())
                .stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsRead()))
                .count());

        if (message.getReplyToMessageId() != null) {
            messageRepository.findById(message.getReplyToMessageId()).ifPresent(reply -> {
                MessageDTO replyDto = new MessageDTO();
                replyDto.setId(reply.getId());
                replyDto.setChatId(reply.getChatId());
                replyDto.setSenderId(reply.getSenderId());
                replyDto.setContent(Boolean.TRUE.equals(reply.getIsRecalled()) ? "" : reply.getContent());
                replyDto.setMessageType(reply.getMessageType());
                replyDto.setIsRecalled(Boolean.TRUE.equals(reply.getIsRecalled()));
                replyDto.setCreatedAt(reply.getCreatedAt());
                userRepository.findById(reply.getSenderId()).ifPresent(replySender -> {
                    replyDto.setSenderNickname(replySender.getNickname());
                    replyDto.setSenderAvatar(replySender.getAvatarUrl());
                });
                dto.setReplyToMessage(replyDto);
            });
        }

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
