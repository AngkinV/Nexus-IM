package com.nexus.chat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.CompanionConversationGuard;
import com.nexus.chat.service.skills.CompanionSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/companion/skills")
@RequiredArgsConstructor
public class CompanionSkillController {
    private final CompanionSkillService skills;
    private final CompanionConversationGuard guard;
    @org.springframework.beans.factory.annotation.Autowired
    private com.nexus.chat.service.skills.CompanionSkillTrialService trials;
    public record TrialRequest(Long roleId,String input,Boolean replaceIdentity) {}
    public record ArtifactRequest(String sessionId,String artifactId) {}
    public record PublishRequest(String expectedHash) {}
    public record BindingRequest(Long skillId, int revision, Boolean enabled, Boolean replaceIdentity, Long expectedVersion) {}

    @GetMapping
    public List<CompanionSkillService.SkillView> list() { return skills.list(CurrentUser.require(null)); }
    @GetMapping("/{id}")
    public CompanionSkillService.SkillView get(@PathVariable Long id) { return skills.get(CurrentUser.require(null), id); }
    @PostMapping({"", "/import"})
    public CompanionSkillService.SkillView create(@RequestBody String body) { return skills.create(CurrentUser.require(null), body); }
    @PostMapping("/from-artifact")
    public CompanionSkillService.SkillView fromArtifact(@RequestBody ArtifactRequest body) {
        return trials.fromArtifact(CurrentUser.require(null),body.sessionId(),body.artifactId());
    }
    @PostMapping("/{id}/versions/{revision}/trial")
    public Map<String,Object> trial(@PathVariable Long id,@PathVariable int revision,@RequestBody TrialRequest body) {
        if (body.roleId()==null || body.roleId()<=0 || revision<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Role and revision required");
        return trials.trial(CurrentUser.require(null),id,revision,body.roleId(),body.input(),Boolean.TRUE.equals(body.replaceIdentity()));
    }

    @PostMapping("/{id}/versions")
    public CompanionSkillService.SkillView revise(@PathVariable Long id, @RequestBody String body) {
        return skills.revise(CurrentUser.require(null), id, body);
    }
    @PostMapping("/{id}/versions/{revision}/publish")
    public CompanionSkillService.SkillView publish(@PathVariable Long id, @PathVariable int revision, @RequestBody PublishRequest body) {
        return skills.publish(CurrentUser.require(null), id, revision, body.expectedHash());
    }
    @GetMapping("/{id}/versions/{revision}/export")
    public JsonNode export(@PathVariable Long id, @PathVariable int revision) {
        return skills.export(CurrentUser.require(null), id, revision);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        skills.delete(CurrentUser.require(null), id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/roles/{roleId}/bindings")
    public List<CompanionSkillService.BindingView> bindings(@PathVariable Long roleId) {
        return skills.listBindings(CurrentUser.require(null), roleId);
    }
    @PostMapping("/roles/{roleId}/bindings")
    public List<CompanionSkillService.BindingView> bind(@PathVariable Long roleId, @RequestBody BindingRequest body) {
        Long user = CurrentUser.require(null);
        if (body.skillId() == null || body.revision() < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Skill version is required");
        try (var ignored = guard.acquire(user, roleId)) {
            return skills.bind(user, roleId, body.skillId(), body.revision(), !Boolean.FALSE.equals(body.enabled()),
                    Boolean.TRUE.equals(body.replaceIdentity()), body.expectedVersion());
        }
    }
    @DeleteMapping("/roles/{roleId}/bindings/{skillId}")
    public List<CompanionSkillService.BindingView> unbind(@PathVariable Long roleId, @PathVariable Long skillId,
                                                       @RequestParam(required=false) Long expectedVersion) {
        Long user = CurrentUser.require(null);
        try (var ignored = guard.acquire(user, roleId)) { return skills.unbind(user, roleId, skillId, expectedVersion); }
    }
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String,String>> conflict(Exception ignored) {
        return ResponseEntity.status(409).body(Map.of("code","SKILL_CHANGED","message","Skill changed; reload it"));
    }
}
