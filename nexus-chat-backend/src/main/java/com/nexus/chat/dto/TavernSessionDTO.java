package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One visit to the tavern, as the client sees it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TavernSessionDTO {
    private Long id;
    private Long roleId;
    private String sceneKey;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime closedAt;
    /** What she said on the way out. Null while the evening is still going. */
    private String closingLine;
    /** The sentence kept as long-term memory. */
    private String summary;
    /** Messages exchanged in this session — the archive list needs a count. */
    private Integer messageCount;
}
