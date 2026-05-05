package com.nexus.chat.dto;

import com.nexus.chat.model.Message;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {
    private Long id;
    private Long chatId;
    private Long senderId;
    private String senderNickname;
    private String senderAvatar;
    private String content;
    private Message.MessageType messageType;
    private String fileUrl;
    private LocalDateTime createdAt;
    private Boolean isRead;

    // Sequence + deduplication (Phase 3)
    private Long sequenceNumber;
    private String clientMsgId;

    // 文件消息扩展字段
    private String fileId;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private String downloadUrl;
    private String previewUrl;
}
