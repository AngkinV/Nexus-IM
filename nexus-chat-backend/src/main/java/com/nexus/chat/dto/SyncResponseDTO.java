package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncResponseDTO {
    private List<MessageDTO> messages;
    private List<ChatDTO> chats;
    private List<ContactDTO> contacts;
}
