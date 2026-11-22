package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionRoleDTO {
    private Long id;
    private String name;
    private List<String> traits;
    private String tone;
    private String baselineMood;
    private String avatarUrl;
    private String modelUrl;
    private String modelType;
    private Boolean active;
    /** "regular" (常驻) or "guest" (过客, met in one evening). */
    private String kind;

    /**
     * When this character was last spoken to. Drives list order — the seat list
     * sorts by recency, like the chat list, never by which one is selected.
     * Null when no conversation exists yet; those sort last.
     */
    private java.time.LocalDateTime lastMessageAt;

    /** What she is doing right now, shown as the row's subtitle. */
    private String presence;

    /**
     * The last thing said in this conversation — hers or the user's — shown as
     * the row's preview under the name, exactly like a chat list row.
     * Null when nothing has been said yet.
     */
    private String lastMessage;
    /** Backstory, in the card's own words. */
    private String background;
    /** Her opening line for a new evening. */
    private String greeting;
    private boolean conversationHidden;
}
