package com.nexus.chat.controller;

import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.CompanionConversationHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companion/conversations")
@RequiredArgsConstructor
public class CompanionConversationController {
    private final CompanionConversationHistoryService historyService;

    @PostMapping("/{roleId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void restore(@PathVariable Long roleId,
                        @RequestParam(name = "userId", required = false) Long claimedUserId) {
        historyService.restore(CurrentUser.require(claimedUserId), roleId);
    }

    @DeleteMapping("/{roleId}")
    public CompanionConversationHistoryService.ClearedHistory clear(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        return historyService.clear(CurrentUser.require(claimedUserId), roleId);
    }
}
