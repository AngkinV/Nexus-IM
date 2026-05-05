package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for updating user privacy settings
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrivacySettingsDTO {
    private Boolean showOnlineStatus;
    private Boolean showLastSeen;
    private Boolean showEmail;
    private Boolean showPhone;

    /**
     * 好友验证方式: DIRECT(直接同意) 或 VERIFY(验证同意)
     */
    private String friendRequestMode;
}
