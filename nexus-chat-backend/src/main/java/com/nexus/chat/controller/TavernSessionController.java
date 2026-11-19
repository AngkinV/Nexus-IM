package com.nexus.chat.controller;

import com.nexus.chat.dto.CompanionRoleDTO;
import com.nexus.chat.dto.TavernSessionDTO;
import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.CompanionService;
import com.nexus.chat.service.TavernSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The tavern's own clock: opening an evening, closing it, and the two moves that
 * only exist across evenings — inviting a guest and keeping one.
 *
 * Separate from CompanionController because these are not about a character's
 * profile; they are about a visit.
 */
@Slf4j
@RestController
@RequestMapping("/api/tavern")
@RequiredArgsConstructor
public class TavernSessionController {

    private final TavernSessionService sessionService;
    private final CompanionService companionService;

    /**
     * Enter the tavern: return the evening in progress, opening one if needed.
     * Idempotent, so the client can call it on every mount.
     */
    @PostMapping("/sessions/open")
    public ResponseEntity<TavernSessionDTO> open(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            Long roleId = Long.valueOf(request.get("roleId").toString());
            String sceneKey = request.get("sceneKey") == null ? null : request.get("sceneKey").toString();
            return ResponseEntity.ok(sessionService.openSession(userId, roleId, sceneKey));
        } catch (Exception e) {
            log.error("Open tavern session failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /** The evening in progress, or 204 when there is none. */
    @GetMapping("/sessions/active")
    public ResponseEntity<TavernSessionDTO> active(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestParam Long roleId) {
        Long userId = CurrentUser.require(claimedUserId);
        return sessionService.findActive(userId, roleId)
                .map(session -> ResponseEntity.ok(sessionService.describe(userId, session.getId())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Close the evening. Safe to call twice. */
    @PostMapping("/sessions/{sessionId}/close")
    public ResponseEntity<TavernSessionDTO> close(
            @PathVariable Long sessionId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(sessionService.closeSession(userId, sessionId));
        } catch (Exception e) {
            log.error("Close tavern session failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /** Past evenings, newest first. */
    @GetMapping("/sessions")
    public ResponseEntity<List<TavernSessionDTO>> history(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestParam Long roleId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(sessionService.listClosed(userId, roleId));
        } catch (Exception e) {
            log.error("List tavern sessions failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /** Fill the empty seat with a new guest. Costs one model call when one is configured. */
    @PostMapping("/roles/invite")
    public ResponseEntity<CompanionRoleDTO> invite(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody(required = false) Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            String sceneKey = request == null || request.get("sceneKey") == null
                    ? null : request.get("sceneKey").toString();
            return ResponseEntity.ok(companionService.toRoleDTO(sessionService.inviteRole(userId, sceneKey)));
        } catch (Exception e) {
            log.error("Invite tavern guest failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /** Keep a guest: they move from 过往相逢 into 当前席位. */
    @PostMapping("/roles/{roleId}/adopt")
    public ResponseEntity<Void> adopt(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody(required = false) Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            Long sessionId = request == null || request.get("sessionId") == null
                    ? null : Long.valueOf(request.get("sessionId").toString());
            sessionService.adoptRole(userId, roleId, sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Adopt tavern guest failed", e);
            return ResponseEntity.badRequest().build();
        }
    }
}
