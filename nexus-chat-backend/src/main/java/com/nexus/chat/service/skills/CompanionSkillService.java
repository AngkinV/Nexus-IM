package com.nexus.chat.service.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/** Skills live independently of workbench sessions. Revisions are append-only. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanionSkillService {
    private final CompanionSkillRepository skills;
    private final CompanionSkillVersionRepository versions;
    private final CompanionSkillBindingRepository bindings;
    private final CompanionRoleRepository roles;
    private final SkillPackageValidator validator;

    public record VersionView(int revision, String status, String hash, String name, String type,
                              String packageVersion, String sourceArtifactId) {}
    public record SkillView(Long id, String name, String description, int latestRevision, List<VersionView> versions) {}
    public record BindingView(Long id, Long skillId, int revision, String name, String type, boolean enabled,
                              boolean replaceIdentity, Long version, String hash) {}
    public record BoundSkill(Long skillId, int revision, boolean replaceIdentity, SkillPackageValidator.Parsed parsed) {}

    private void lockCapacityOwner(Long owner) {
        skills.lockOwnerBudget(owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Account not found"));
    }

    private void requireCapacity(Long owner,int extra) {
        // 5 Mi characters bounds UTF-8 storage to at most 20 MiB per account, including immutable history.
        if(versions.storedCharacters(owner)+extra>5L*1024*1024) throw bad("Skill text storage limit reached");
    }

    public List<SkillView> list(Long owner) {
        return skills.findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(owner).stream().map(s -> view(owner, s)).toList();
    }
    public SkillView get(Long owner, Long id) { return view(owner, requireSkill(owner, id, false)); }
    public JsonNode export(Long owner, Long id, int revision) {
        requireSkill(owner, id, false);
        return validator.readJson(requireVersion(owner, id, revision).getPackageJson());
    }

    @Transactional
    public SkillView create(Long owner, String raw) { return createFromArtifact(owner, raw, null); }

    @Transactional
    public SkillView createFromArtifact(Long owner, String raw, String artifactId) {
        var parsed = validator.parse(raw);
        lockCapacityOwner(owner);
        requireCapacity(owner,parsed.canonicalJson().length());
        if (skills.countByUserIdAndDeletedFalse(owner) >= 128) throw bad("Skill asset limit reached");
        CompanionSkill skill = new CompanionSkill();
        skill.setUserId(owner); skill.setName(parsed.name()); skill.setDescription(parsed.description()); skill.setLatestRevision(1);
        skills.saveAndFlush(skill);
        versions.saveAndFlush(newVersion(owner, skill.getId(), 1, parsed, artifactId));
        return view(owner, skill);
    }

    @Transactional
    public SkillView revise(Long owner, Long id, String raw) {
        var root = validator.readJson(raw);
        var parsed = validator.parse(root);
        lockCapacityOwner(owner);
        CompanionSkill skill = requireSkill(owner, id, true);
        CompanionSkillVersion previous = requireVersion(owner, id, skill.getLatestRevision());
        requireHash(previous.getHash(), root.path("expectedHash").asText(null));
        if (skill.getLatestRevision() >= 100) throw bad("Skill revision limit reached");
        requireCapacity(owner,parsed.canonicalJson().length());
        int next = skill.getLatestRevision() + 1;
        versions.saveAndFlush(newVersion(owner, id, next, parsed, previous.getSourceArtifactId()));
        skill.setLatestRevision(next); skill.setName(parsed.name()); skill.setDescription(parsed.description());
        skills.save(skill);
        return view(owner, skill);
    }

    @Transactional
    public SkillView publish(Long owner, Long id, int revision, String expectedHash) {
        CompanionSkill skill = requireSkill(owner, id, true);
        CompanionSkillVersion version = requireVersion(owner, id, revision);
        requireHash(version.getHash(), expectedHash);
        // The same validator is authoritative at both import and publish boundaries.
        validator.parse(version.getPackageJson());
        if (version.getStatus() != CompanionSkillVersion.Status.PUBLISHED) {
            version.setStatus(CompanionSkillVersion.Status.PUBLISHED);
            version.setPublishedAt(LocalDateTime.now()); versions.save(version);
        }
        return view(owner, skill);
    }

    @Transactional
    public void delete(Long owner, Long id) {
        CompanionSkill skill = requireSkill(owner, id, true);
        skill.setDeleted(true); skills.save(skill);
        for (var binding : bindings.findByUserIdAndSkillId(owner, id)) {
            binding.setEnabled(false); bindings.save(binding);
        }
        // Preserve immutable revisions for existing scene snapshots and provenance.
    }

    public List<BindingView> listBindings(Long owner, Long roleId) {
        requireRole(owner, roleId);
        return bindings.findByUserIdAndRoleIdOrderByIdAsc(owner, roleId).stream().map(b -> {
            var v = requireVersion(owner, b.getSkillId(), b.getRevision());
            return new BindingView(b.getId(), b.getSkillId(), b.getRevision(), v.getName(), v.getType(),
                    b.isEnabled(), b.isReplaceIdentity(), b.getVersion(), v.getHash());
        }).toList();
    }

    @Transactional
    public List<BindingView> bind(Long owner, Long roleId, Long id, int revision, boolean enabled,
                                  boolean replaceIdentity, Long expectedVersion) {
        requireRole(owner, roleId); requireSkill(owner, id, false);
        CompanionSkillVersion target = requireVersion(owner, id, revision);
        if (target.getStatus() != CompanionSkillVersion.Status.PUBLISHED) throw bad("Publish the skill before binding it");
        var existing = bindings.findByUserIdAndRoleIdAndSkillId(owner, roleId, id);
        if (existing.isPresent() && !Objects.equals(existing.get().getVersion(), expectedVersion)) throw conflict("Skill binding changed; reload it");
        var all = new ArrayList<>(bindings.findByUserIdAndRoleIdOrderByIdAsc(owner, roleId));
        if (existing.isEmpty() && all.size() >= 8) throw bad("A role can bind at most eight skills");
        CompanionSkillBinding binding = existing.orElseGet(CompanionSkillBinding::new);
        binding.setUserId(owner); binding.setRoleId(roleId); binding.setSkillId(id); binding.setRevision(revision);
        binding.setEnabled(enabled); binding.setReplaceIdentity(replaceIdentity);
        all.removeIf(b -> Objects.equals(b.getSkillId(), id)); all.add(binding);
        validateConflicts(owner, all);
        bindings.saveAndFlush(binding);
        return listBindings(owner, roleId);
    }

    @Transactional
    public List<BindingView> unbind(Long owner, Long roleId, Long id, Long expectedVersion) {
        requireRole(owner, roleId);
        var found = bindings.findByUserIdAndRoleIdAndSkillId(owner, roleId, id);
        if (found.isPresent()) {
            if (!Objects.equals(found.get().getVersion(), expectedVersion)) throw conflict("Skill binding changed; reload it");
            bindings.delete(found.get()); bindings.flush();
        }
        return listBindings(owner, roleId);
    }

    /** Called only at a scene boundary; the caller persists the returned package snapshot. */
    public List<BoundSkill> runtimeBindings(Long owner, Long roleId) {
        requireRole(owner, roleId);
        List<BoundSkill> result = new ArrayList<>();
        for (var b : bindings.findByUserIdAndRoleIdOrderByIdAsc(owner, roleId)) {
            if (!b.isEnabled()) continue;
            var skill = skills.findByIdAndUserId(b.getSkillId(), owner);
            if (skill.isEmpty() || skill.get().isDeleted()) continue;
            var version = requireVersion(owner, b.getSkillId(), b.getRevision());
            if (version.getStatus() == CompanionSkillVersion.Status.PUBLISHED) {
                result.add(new BoundSkill(b.getSkillId(), b.getRevision(), b.isReplaceIdentity(), validator.parse(version.getPackageJson())));
            }
        }
        return List.copyOf(result);
    }

    private void validateConflicts(Long owner, List<CompanionSkillBinding> all) {
        Map<String,String> effective = new HashMap<>();
        for (var b : all) if (b.isEnabled()) {
            var p = validator.parse(requireVersion(owner, b.getSkillId(), b.getRevision()).getPackageJson());
            for (var e : p.patch().entrySet()) {
                if (!b.isReplaceIdentity() && Set.of("name","background","greeting").contains(e.getKey())) continue;
                String previous = effective.putIfAbsent(e.getKey(), e.getValue());
                if (previous != null && !previous.equals(e.getValue())) throw conflict("Enabled skills conflict on persona field: " + e.getKey());
            }
        }
    }
    private CompanionRole requireRole(Long owner, Long id) {
        return roles.findByIdAndUserId(id, owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));
    }
    private CompanionSkill requireSkill(Long owner, Long id, boolean lock) {
        var result = lock ? skills.lockOwned(id, owner) : skills.findByIdAndUserId(id, owner);
        return result.filter(s -> !s.isDeleted()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Skill not found"));
    }
    private CompanionSkillVersion requireVersion(Long owner, Long id, int revision) {
        return versions.findBySkillIdAndUserIdAndRevision(id, owner, revision)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Skill version not found"));
    }
    private SkillView view(Long owner, CompanionSkill skill) {
        var list = versions.findBySkillIdAndUserIdOrderByRevisionDesc(skill.getId(), owner).stream()
                .map(v -> new VersionView(v.getRevision(), v.getStatus().name().toLowerCase(Locale.ROOT), v.getHash(), v.getName(), v.getType(), v.getPackageVersion(), v.getSourceArtifactId())).toList();
        return new SkillView(skill.getId(), skill.getName(), skill.getDescription(), skill.getLatestRevision(), list);
    }
    private static CompanionSkillVersion newVersion(Long owner, Long id, int revision, SkillPackageValidator.Parsed p, String source) {
        var v = new CompanionSkillVersion(); v.setUserId(owner); v.setSkillId(id); v.setRevision(revision);
        v.setName(p.name()); v.setDescription(p.description()); v.setType(p.type()); v.setPackageVersion(p.packageVersion());
        v.setHash(p.hash()); v.setPackageJson(p.canonicalJson()); v.setSourceArtifactId(source);
        return v;
    }
    private static void requireHash(String actual, String expected) {
        if (expected == null || !Objects.equals(actual, expected)) throw conflict("Skill changed; reload it before editing or publishing");
    }
    private static ResponseStatusException bad(String s) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,s); }
    private static ResponseStatusException conflict(String s) { return new ResponseStatusException(HttpStatus.CONFLICT,s); }
}
