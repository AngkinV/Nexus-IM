package com.nexus.chat.service.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.TavernSession;
import com.nexus.chat.repository.TavernSessionRepository;
import com.nexus.chat.service.CompanionPromptBuilder;
import com.nexus.chat.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Shared by real replies and isolated skill trials. Never mutates the original role card. */
@Service @RequiredArgsConstructor
public class CompanionSkillRuntime {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PROMPT_BUDGET = 24_000;
    private final CompanionSkillService skills;
    private final SkillPackageValidator validator;
    private final TavernSessionRepository sessions;
    public record Frozen(Long skillId, int revision, boolean replaceIdentity, String packageJson) {}
    public record Snapshot(Long owner, Long roleId, Map<String,String> base, List<Frozen> skills) {}
    public record Receipt(Long skillId, int revision, String name, String type, String hash, String status,
                          List<String> files, List<String> omittedReferences) {}
    public record Frame(CompanionRole role, String prompt, List<Receipt> skills, String summary) {
        public CompanionPromptBuilder.Context decorate(CompanionPromptBuilder.Context original) {
            return new CompanionPromptBuilder.Context(role, original.sceneKey(), original.personaLock(),
                    original.intimacy(), original.trust(), original.memories(), original.withState(), prompt, summary);
        }
    }

    /** New scenes take the current explicit bindings. Existing scenes keep their immutable snapshot. */
    public void freeze(TavernSession session, CompanionRole role) {
        session.setSkillSnapshot(encode(snapshot(role, skills.runtimeBindings(session.getUserId(), session.getRoleId()))));
    }
    public Frame frame(Long owner, CompanionRole role, TavernSession session, String query) {
        Snapshot snapshot;
        if (session == null) {
            snapshot = snapshot(role, skills.runtimeBindings(owner, role.getId()));
        } else {
            if (!Objects.equals(owner, session.getUserId()) || !Objects.equals(role.getId(), session.getRoleId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Scene not found");
            }
            if (session.getSkillSnapshot() == null) {
                // Pre-feature active scenes did not load any skills. Do not apply a new binding mid-scene.
                session.setSkillSnapshot(encode(snapshot(role, List.of())));
                sessions.save(session);
            }
            snapshot = decode(session.getSkillSnapshot(), owner, role.getId());
        }
        return assemble(role, snapshot, query, session == null ? "" : session.getRollingSummary());
    }
    public Frame trial(Long owner, CompanionRole role, Long skillId, int revision, String raw, String query, boolean replaceIdentity) {
        if (!Objects.equals(owner, role.getUserId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Role not found");
        var p = validator.parse(raw);
        return assemble(role, new Snapshot(owner,role.getId(),base(role),List.of(new Frozen(skillId,revision,replaceIdentity,p.canonicalJson()))),query,"");
    }
    private Frame assemble(CompanionRole original, Snapshot snapshot, String query, String summary) {
        CompanionRole role = new CompanionRole(); BeanUtils.copyProperties(original, role);
        Map<String,String> base = snapshot.base();
        role.setName(base.get("name")); role.setTraits(base.get("traits")); role.setTone(base.get("tone"));
        role.setBaselineMood(base.get("baselineMood")); role.setBackground(base.get("background")); role.setGreeting(base.get("greeting"));
        StringBuilder prompt = new StringBuilder(); List<Receipt> receipts = new ArrayList<>();
        String topic = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (Frozen frozen : snapshot.skills()) {
            var p = validator.parse(frozen.packageJson());
            boolean selected = p.type().equals("personality") || p.triggers().isEmpty()
                    || p.triggers().stream().anyMatch(t -> topic.contains(t.toLowerCase(Locale.ROOT)));
            List<String> loaded = new ArrayList<>(); List<String> omitted = new ArrayList<>();
            String state = "not_triggered";
            if (selected && prompt.length() + p.instructions().length() + 200 <= PROMPT_BUDGET) {
                state = "loaded"; loaded.add("SKILL.md");
                prompt.append("【").append(p.name()).append(" v").append(frozen.revision()).append("】\n")
                        .append(p.instructions()).append('\n');
                for (var file : p.files().entrySet()) {
                    if (file.getKey().equals("SKILL.md") || !p.instructions().contains(file.getKey())) continue;
                    if (prompt.length() + file.getValue().length() + file.getKey().length() + 10 <= PROMPT_BUDGET) {
                        prompt.append("参考：").append(file.getKey()).append('\n').append(file.getValue()).append('\n');
                        loaded.add(file.getKey());
                    } else omitted.add(file.getKey());
                }
                applyPatch(role, p.patch(), frozen.replaceIdentity());
            } else if (selected) state = "budget_exceeded";
            receipts.add(new Receipt(frozen.skillId(),frozen.revision(),p.name(),p.type(),p.hash(),state,List.copyOf(loaded),List.copyOf(omitted)));
        }
        return new Frame(role,prompt.toString(),List.copyOf(receipts),summary == null ? "" : summary);
    }
    private static void applyPatch(CompanionRole role, Map<String,String> patch, boolean replaceIdentity) {
        patch.forEach((field,value) -> {
            switch(field) {
                case "traits" -> role.setTraits(JsonUtil.toJson(Arrays.asList(value.split("[、,，]"))));
                case "tone" -> role.setTone(value);
                case "baselineMood" -> role.setBaselineMood(value);
                case "name" -> { if(replaceIdentity) role.setName(value); }
                case "background" -> { if(replaceIdentity) role.setBackground(value); }
                case "greeting" -> { if(replaceIdentity) role.setGreeting(value); }
            }
        });
    }
    private static Snapshot snapshot(CompanionRole role,List<CompanionSkillService.BoundSkill> bindings) {
        return new Snapshot(role.getUserId(),role.getId(),base(role),bindings.stream()
                .map(b -> new Frozen(b.skillId(),b.revision(),b.replaceIdentity(),b.parsed().canonicalJson())).toList());
    }
    private static Map<String,String> base(CompanionRole r) {
        Map<String,String> b=new LinkedHashMap<>();
        b.put("name",safe(r.getName())); b.put("traits",safe(r.getTraits())); b.put("tone",safe(r.getTone()));
        b.put("baselineMood",safe(r.getBaselineMood())); b.put("background",safe(r.getBackground())); b.put("greeting",safe(r.getGreeting()));
        return b;
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static Snapshot decode(String raw,Long owner,Long role) {
        try {
            Snapshot value=JSON.readValue(raw,Snapshot.class);
            if (!Objects.equals(owner,value.owner()) || !Objects.equals(role,value.roleId()) || value.skills()==null || value.base()==null) throw new IllegalArgumentException();
            return value;
        } catch(Exception e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Scene skill snapshot is invalid; close this scene before continuing"); }
    }
    public static String encode(Object value) {
        try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Cannot encode context receipt"); }
    }
    public static JsonNode receiptNode(String raw) {
        if(raw==null || raw.isBlank())return null;
        try { return JSON.readTree(raw); } catch(Exception e) { return null; }
    }
}
