package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketMessage {
    private MessageType type;
    private Object payload;

    public enum MessageType {
        // Chat messages
        CHAT_MESSAGE,
        MESSAGE_READ,
        TYPING,

        // Chat events
        CHAT_CREATED,
        CHAT_DISABLED,

        // User status
        USER_ONLINE,
        USER_OFFLINE,
        USER_STATUS_CHANGED,
        USER_PROFILE_UPDATED,  // User avatar/nickname updated

        // Group events
        GROUP_CREATED,
        GROUP_UPDATED,
        GROUP_DELETED,
        GROUP_MEMBER_JOINED,
        GROUP_MEMBER_LEFT,
        GROUP_MESSAGE,
        GROUP_ADMIN_CHANGED,
        GROUP_OWNERSHIP_TRANSFERRED,

        // Contact events
        CONTACT_ADDED,
        CONTACT_REMOVED,
        CONTACT_STATUS_CHANGED,

        // Contact request events
        CONTACT_REQUEST,
        CONTACT_REQUEST_ACCEPTED,
        CONTACT_REQUEST_REJECTED,

        // Message delivery ACK (Phase 3)
        MESSAGE_ACK,              // Server confirmed receipt
        MESSAGE_DELIVERED,        // Delivered to recipient
        MESSAGE_DELIVERY_FAILED,  // Delivery failed

        // Sync (Phase 3/4)
        SYNC_REQUEST,             // Client requests missing messages
        SYNC_RESPONSE,            // Server returns missed messages

        // Call signaling (Phase 6)
        CALL_INVITE,
        CALL_ACCEPT,
        CALL_CANCEL,
        CALL_END,
        CALL_OFFER,
        CALL_ANSWER,
        CALL_ICE_CANDIDATE,
        CALL_REJECT,
        CALL_BUSY,
        CALL_TIMEOUT,
        CALL_MUTE,
        CALL_VIDEO_TOGGLE,

        // Error
        ERROR
    }
}
