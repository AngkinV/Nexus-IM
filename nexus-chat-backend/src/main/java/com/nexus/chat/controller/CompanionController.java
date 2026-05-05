package com.nexus.chat.controller;

import com.nexus.chat.dto.*;
import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.service.CompanionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/companion")
@RequiredArgsConstructor
public class CompanionController {

    private final CompanionService companionService;

    @PostMapping("/roles/init")
    public ResponseEntity<List<CompanionRoleDTO>> initRoles(@RequestParam Long userId) {
        try {
            return ResponseEntity.ok(companionService.initDefaultRoles(userId));
        } catch (Exception e) {
            log.error("Init roles failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/roles")
    public ResponseEntity<List<CompanionRoleDTO>> getRoles(@RequestParam Long userId) {
        try {
            return ResponseEntity.ok(companionService.getRoles(userId));
        } catch (Exception e) {
            log.error("Get roles failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/roles/{roleId}")
    public ResponseEntity<CompanionRoleDTO> updateRole(
            @PathVariable Long roleId,
            @RequestParam Long userId,
            @RequestBody CompanionRoleDTO update) {
        try {
            return ResponseEntity.ok(companionService.updateRole(userId, roleId, update));
        } catch (Exception e) {
            log.error("Update role failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/conversations/{roleId}")
    public ResponseEntity<List<CompanionMessageDTO>> getConversation(
            @PathVariable Long roleId,
            @RequestParam Long userId) {
        try {
            return ResponseEntity.ok(companionService.getConversation(userId, roleId));
        } catch (Exception e) {
            log.error("Get conversation failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/messages")
    public ResponseEntity<CompanionChatResponse> sendMessage(
            @RequestParam Long userId,
            @RequestBody Map<String, Object> request) {
        try {
            Long roleId = Long.valueOf(request.get("roleId").toString());
            String content = request.get("content") == null ? "" : request.get("content").toString();
            return ResponseEntity.ok(companionService.sendMessage(userId, roleId, content));
        } catch (Exception e) {
            log.error("Send companion message failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/memories")
    public ResponseEntity<List<CompanionMemoryDTO>> getMemories(
            @RequestParam Long userId,
            @RequestParam Long roleId) {
        try {
            return ResponseEntity.ok(companionService.getMemories(userId, roleId));
        } catch (Exception e) {
            log.error("Get memories failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/memories")
    public ResponseEntity<CompanionMemoryDTO> createMemory(
            @RequestParam Long userId,
            @RequestBody Map<String, Object> request) {
        try {
            Long roleId = Long.valueOf(request.get("roleId").toString());
            String type = request.get("type") != null ? request.get("type").toString() : "mid";
            String content = request.get("content") != null ? request.get("content").toString() : "";
            return ResponseEntity.ok(companionService.createMemory(userId, roleId, type, content));
        } catch (Exception e) {
            log.error("Create memory failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/memories/{id}/confirm")
    public ResponseEntity<CompanionMemoryDTO> confirmMemory(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            return ResponseEntity.ok(companionService.confirmMemory(userId, id));
        } catch (Exception e) {
            log.error("Confirm memory failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/memories/{id}")
    public ResponseEntity<Void> deleteMemory(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            companionService.deleteMemory(userId, id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Delete memory failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/memories")
    public ResponseEntity<Void> clearMemories(
            @RequestParam Long userId,
            @RequestParam Long roleId) {
        try {
            companionService.clearMemories(userId, roleId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Clear memories failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/growth/{roleId}")
    public ResponseEntity<CompanionGrowthDTO> getGrowth(
            @PathVariable Long roleId,
            @RequestParam Long userId) {
        try {
            return ResponseEntity.ok(companionService.getGrowth(userId, roleId));
        } catch (Exception e) {
            log.error("Get growth failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/status/{roleId}")
    public ResponseEntity<CompanionStatusDTO> getStatus(
            @PathVariable Long roleId,
            @RequestParam Long userId) {
        try {
            return ResponseEntity.ok(companionService.getStatus(userId, roleId));
        } catch (Exception e) {
            log.error("Get status failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/status/{roleId}")
    public ResponseEntity<CompanionStatusDTO> updateStatus(
            @PathVariable Long roleId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> request) {
        try {
            String statusType = request.get("statusType") != null ? request.get("statusType").toString() : null;
            String summary = request.get("summary") != null ? request.get("summary").toString() : null;
            return ResponseEntity.ok(companionService.updateStatus(userId, roleId, statusType, summary));
        } catch (Exception e) {
            log.error("Update status failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/model-credential")
    public ResponseEntity<ModelCredentialStatusDTO> saveCredential(
            @RequestParam Long userId,
            @RequestBody Map<String, Object> request) {
        try {
            String provider = request.get("provider") != null ? request.get("provider").toString() : "openai-compatible";
            String apiKey = request.get("apiKey") != null ? request.get("apiKey").toString() : null;
            if (apiKey == null || apiKey.isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(companionService.saveCredential(userId, provider, apiKey));
        } catch (Exception e) {
            log.error("Save credential failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/model-credential/status")
    public ResponseEntity<ModelCredentialStatusDTO> getCredentialStatus(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "openai-compatible") String provider) {
        try {
            return ResponseEntity.ok(companionService.getCredentialStatus(userId, provider));
        } catch (Exception e) {
            log.error("Get credential status failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/model-binding/{roleId}")
    public ResponseEntity<CompanionModelBinding> bindModel(
            @PathVariable Long roleId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> request) {
        try {
            String provider = request.get("provider") != null ? request.get("provider").toString() : "openai-compatible";
            String modelName = request.get("modelName") != null ? request.get("modelName").toString() : null;
            String endpoint = request.get("endpoint") != null ? request.get("endpoint").toString() : null;
            return ResponseEntity.ok(companionService.bindModel(userId, roleId, provider, modelName, endpoint));
        } catch (Exception e) {
            log.error("Bind model failed", e);
            return ResponseEntity.badRequest().build();
        }
    }
}
