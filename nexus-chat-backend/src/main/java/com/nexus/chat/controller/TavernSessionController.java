package com.nexus.chat.controller;

import com.nexus.chat.dto.CompanionRoleDTO;
import com.nexus.chat.dto.TavernSessionDTO;
import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.CompanionModelAssetCatalog;
import com.nexus.chat.service.CompanionService;
import com.nexus.chat.service.TavernSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    private final CompanionModelAssetCatalog modelAssetCatalog;

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

    /**
     * Fill the empty seat with a new guest. Costs one model call when one is
     * configured.
     *
     * {@code modelUrl} is optional: omit it and the tavern picks a model nobody
     * else is wearing; send one from {@code GET /roles/models} to choose it
     * explicitly. A chosen model that cannot be worn is refused, not replaced:
     * 400 with {@code message} one of model_url_invalid, model_type_unsupported,
     * model_not_found, model_file_invalid. 409 invite_in_progress while this
     * account's previous invite is still running; 500 model_catalog_unavailable
     * when the models directory cannot be read.
     */
    @PostMapping("/roles/invite")
    public ResponseEntity<CompanionRoleDTO> invite(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody(required = false) Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            String sceneKey = request == null || request.get("sceneKey") == null
                    ? null : request.get("sceneKey").toString();
            String modelUrl = request == null || request.get("modelUrl") == null
                    ? null : request.get("modelUrl").toString();
            return ResponseEntity.ok(companionService.toRoleDTO(
                    sessionService.inviteRole(userId, sceneKey, modelUrl)));
        } catch (ResponseStatusException e) {
            // The status and code are the contract; the catch-all below would
            // flatten them into an empty 400.
            throw e;
        } catch (Exception e) {
            log.error("Invite tavern guest failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * The models an invited guest can wear: the shared catalogue narrowed to
     * what the tavern stage can load (VRM), followed by the ones this account
     * imported into its own library in settings ({@code owned: true}). An empty
     * array means there is none; a directory or library that cannot be read is a 500
     * with {@code message} model_catalog_unavailable, never an empty array.
     */
    @GetMapping("/roles/models")
    public ResponseEntity<List<Map<String, Object>>> inviteModels(
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (CompanionModelAssetCatalog.ModelAsset asset : modelAssetCatalog.listForStage()) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("fileName", asset.fileName());
            item.put("fileUrl", asset.fileUrl());
            item.put("size", asset.size());
            item.put("type", asset.type());
            item.put("owned", false);
            out.add(item);
        }
        // The models this account imported in settings. They are referenced by a
        // stable id rather than a file name, and only their owner can wear them.
        for (CompanionModelAssetCatalog.ModelAsset asset : sessionService.ownStageModels(userId)) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("fileName", asset.fileName());
            item.put("fileUrl", asset.fileUrl());
            item.put("size", asset.size());
            item.put("type", asset.type());
            item.put("owned", true);
            out.add(item);
        }
        return ResponseEntity.ok(out);
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
