package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SocialLinkDTO {
    private Long id;
    private String platform;
    private String url;
    private LocalDateTime createdAt;
}
