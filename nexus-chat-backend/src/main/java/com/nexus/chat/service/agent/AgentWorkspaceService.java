package com.nexus.chat.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.agent.AgentWorkspace;
import com.nexus.chat.repository.agent.AgentSessionRepository;
import com.nexus.chat.repository.agent.AgentWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The files and tasks that belong to one agent session.
 *
 * A file is uploaded once, then referenced by ID: the model only ever sees the
 * text Python extracted, and a patch applied to it creates a new version rather
 * than changing the original. Source identities (A1, A2…) are never reused, so
 * a citation in an old answer still points at the file it was written about.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWorkspaceService {

    static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    /** Characters of extracted text the model is given per file. */
    static final int TEXT_BUDGET = 32_000;
    static final int MAX_SELECTED = 4;
    private static final Set<String> TEXT_SUFFIXES = Set.of(
            "txt", "md", "markdown", "py", "js", "mjs", "ts", "tsx", "jsx", "json", "patch", "diff",
            "xml", "csv", "html", "css", "yaml", "yml", "toml", "ini", "cfg", "sh", "sql", "java",
            "kt", "go", "rs", "rb", "php", "c", "h", "cpp", "hpp", "log");
    private static final Set<String> DOCUMENT_SUFFIXES = Set.of("pdf", "docx");

    private final ObjectMapper mapper;
    private final AgentWorkspaceRepository workspaces;
    private final AgentSessionRepository sessions;
    private final AgentRunService runs;
    private final AgentGatewayService python;

    // ----- attachments -----

    public List<Map<String, Object>> listAttachments(Long userId, String sessionId) {
        return attachments(workspace(userId, sessionId, false));
    }

    public Map<String, Object> upload(Long userId, String sessionId, MultipartFile file) {
        AgentWorkspace workspace = workspace(userId, sessionId, true);
        String name = file == null ? null : file.getOriginalFilename();
        String suffix = checkUpload(file, name);
        byte[] bytes = read(file);

        Map<String, Object> extracted;
        try {
            extracted = python.extractAttachment(userId, name, bytes);
        } catch (AgentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The file could not be read");
        }
        String content = extracted == null ? null : (String) extracted.get("content");
        Object reported = extracted == null ? null : extracted.get("sizeBytes");
        if (content == null || !(reported instanceof Number number) || number.longValue() != bytes.length) {
            // Python reports the size it actually read; a different number means the
            // answer is not about these bytes.
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The extracted text does not match the upload");
        }

        // The model reads at most this much per file; the upload itself is kept whole,
        // so a later patch applies to the original bytes rather than to the excerpt.
        String visible = capCodePoints(content, TEXT_BUDGET);
        boolean capped = Boolean.TRUE.equals(extracted.get("truncated")) || visible.length() < content.length();

        String id = "att_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("id", id);
        attachment.put("fileName", name);
        attachment.put("content", visible);
        attachment.put("sha256", sha256(visible));
        attachment.put("sizeBytes", bytes.length);
        attachment.put("truncated", capped);
        attachment.put("sourceId", "A" + workspace.getNextSourceNumber());
        attachment.put("version", 1);
        attachment.put("createdAt", Instant.now().toString());

        List<Map<String, Object>> existing = attachments(workspace);
        existing.add(attachment);
        workspace.setNextSourceNumber(workspace.getNextSourceNumber() + 1);
        workspace.getUploadBytes().put(id, bytes);
        save(workspace, existing, tasks(workspace));
        return new LinkedHashMap<>(attachment);
    }

    public void deleteAttachment(Long userId, String sessionId, String attachmentId) {
        AgentWorkspace workspace = workspace(userId, sessionId, false);
        List<Map<String, Object>> existing = attachments(workspace);
        if (existing.stream().noneMatch(item -> attachmentId.equals(item.get("id")))) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Attachment not found");
        }
        existing.removeIf(item -> attachmentId.equals(item.get("id")));
        workspace.getUploadBytes().remove(attachmentId);
        save(workspace, existing, tasks(workspace));
    }

    /**
     * The attachments a turn will use, in the order they were chosen. Only an ID and
     * the text are passed on; a client cannot supply a path or extra fields.
     */
    public List<Map<String, Object>> resolveAttachments(Long userId, String sessionId, List<String> ids) {
        AgentWorkspace workspace = workspace(userId, sessionId, false);
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > MAX_SELECTED) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Select at most four attachments");
        }
        if (ids.stream().distinct().count() != ids.size()) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The same attachment was chosen twice");
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        attachments(workspace).forEach(item -> byId.put((String) item.get("id"), item));
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> item = byId.get(id);
            if (item == null) {
                throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Attachment not found");
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("id", item.get("id"));
            ref.put("fileName", item.get("fileName"));
            ref.put("content", item.get("content"));
            ref.put("sha256", item.get("sha256"));
            resolved.add(ref);
        }
        return resolved;
    }

    // ----- artifacts -----

    public Map<String, Object> previewPatch(Long userId, String sessionId, String artifactId, String baseAttachmentId) {
        AgentWorkspace workspace = workspace(userId, sessionId, false);
        Map<String, Object> artifact = patchArtifact(userId, sessionId, artifactId);
        Map<String, Object> base = attachment(workspace, baseAttachmentId);
        String original = (String) base.get("content");
        String patch = (String) artifact.get("content");
        Map<String, Object> applied = python.previewArtifactPatch(userId, (String) base.get("fileName"), original, patch);
        String after = applied == null ? null : (String) applied.get("content");
        String baseHash = sha256(original);
        String patchHash = sha256(patch);
        // Python reports what it read; a different digest means it applied something else.
        if (after == null || !baseHash.equals(applied.get("baseHash")) || !patchHash.equals(applied.get("patchHash"))) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The patch does not apply to this file");
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("baseAttachmentId", baseAttachmentId);
        preview.put("before", original);
        preview.put("after", after);
        preview.put("diff", patch);
        preview.put("baseHash", baseHash);
        preview.put("patchHash", patchHash);
        return preview;
    }

    public Map<String, Object> applyPatch(Long userId, String sessionId, String artifactId, String baseAttachmentId,
                                          String baseHash, String patchHash, boolean confirmed) {
        if (!confirmed) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The patch must be confirmed");
        }
        AgentWorkspace workspace = workspace(userId, sessionId, false);
        Map<String, Object> base = attachment(workspace, baseAttachmentId);
        if (!sha256((String) base.get("content")).equals(baseHash)) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The file changed since the preview");
        }
        Map<String, Object> artifact = patchArtifact(userId, sessionId, artifactId);
        if (!sha256((String) artifact.get("content")).equals(patchHash)) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The patch changed since the preview");
        }
        Map<String, Object> applied = python.previewArtifactPatch(
                userId, (String) base.get("fileName"), (String) base.get("content"), (String) artifact.get("content"));
        String after = applied == null ? null : (String) applied.get("content");
        if (after == null || !baseHash.equals(applied.get("baseHash")) || !patchHash.equals(applied.get("patchHash"))) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The patch does not apply to this file");
        }

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", "att_" + UUID.randomUUID().toString().replace("-", ""));
        version.put("fileName", base.get("fileName"));
        version.put("content", after);
        version.put("sha256", sha256(after));
        version.put("sizeBytes", after.getBytes(StandardCharsets.UTF_8).length);
        version.put("truncated", false);
        version.put("sourceId", "A" + workspace.getNextSourceNumber());
        version.put("baseAttachmentId", baseAttachmentId);
        version.put("version", ((Number) base.getOrDefault("version", 1)).intValue() + 1);
        version.put("createdAt", Instant.now().toString());

        List<Map<String, Object>> existing = attachments(workspace);
        existing.add(version);  // the original stays exactly as uploaded
        workspace.setNextSourceNumber(workspace.getNextSourceNumber() + 1);
        workspace.getUploadBytes().put((String) version.get("id"), after.getBytes(StandardCharsets.UTF_8));
        save(workspace, existing, tasks(workspace));
        return new LinkedHashMap<>(version);
    }

    public Map<String, Object> saveArtifact(Long userId, String sessionId, String artifactId) {
        AgentWorkspace workspace = workspace(userId, sessionId, false);
        Map<String, Object> artifact = runs.findArtifact(userId, sessionId, artifactId);
        String content = (String) artifact.get("content");
        if (content == null) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The artifact has no content");
        }
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("id", "att_" + UUID.randomUUID().toString().replace("-", ""));
        saved.put("fileName", artifact.getOrDefault("name", artifactId + ".txt"));
        saved.put("content", content);
        saved.put("sha256", sha256(content));
        saved.put("sizeBytes", content.getBytes(StandardCharsets.UTF_8).length);
        saved.put("truncated", false);
        saved.put("sourceId", "A" + workspace.getNextSourceNumber());
        saved.put("sourceArtifactId", artifactId);
        saved.put("version", 1);
        saved.put("createdAt", Instant.now().toString());
        List<Map<String, Object>> existing = attachments(workspace);
        existing.add(saved);
        workspace.setNextSourceNumber(workspace.getNextSourceNumber() + 1);
        workspace.getUploadBytes().put((String) saved.get("id"), content.getBytes(StandardCharsets.UTF_8));
        save(workspace, existing, tasks(workspace));
        return new LinkedHashMap<>(saved);
    }

    // ----- tasks -----

    public List<Map<String, Object>> listTasks(Long userId, String sessionId) {
        return tasks(workspace(userId, sessionId, false));
    }

    /** Create this artifact's tasks once. Repeating the request keeps the existing ones. */
    public List<Map<String, Object>> createTasks(Long userId, String sessionId, String artifactId) {
        AgentWorkspace workspace = workspace(userId, sessionId, false);
        Map<String, Object> artifact = runs.findArtifact(userId, sessionId, artifactId);
        List<Map<String, Object>> current = tasks(workspace);
        List<Map<String, Object>> created = current.stream()
                .filter(task -> artifactId.equals(task.get("sourceArtifactId"))).toList();
        if (!created.isEmpty()) {
            List<Map<String, Object>> copy = new ArrayList<>();
            for (Map<String, Object> item : created) copy.add(new LinkedHashMap<>(item));
            return copy;
        }
        List<String> titles = taskTitles((String) artifact.get("content"));
        String now = Instant.now().toString();
        List<Map<String, Object>> added = new ArrayList<>();
        for (String title : titles) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", "task_" + UUID.randomUUID().toString().replace("-", ""));
            task.put("title", title);
            task.put("status", "pending");
            task.put("sourceArtifactId", artifactId);
            task.put("createdAt", now);
            added.add(task);
        }
        List<Map<String, Object>> merged = new ArrayList<>(current);
        merged.addAll(added);
        save(workspace, attachments(workspace), merged);
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> item : added) copy.add(new LinkedHashMap<>(item));
        return copy;
    }

    public Map<String, Object> updateTask(Long userId, String sessionId, String taskId, String status) {
        if (!Set.of("pending", "done").contains(status)) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Task status must be pending or done");
        }
        AgentWorkspace workspace = workspace(userId, sessionId, false);
        List<Map<String, Object>> current = tasks(workspace);
        Map<String, Object> found = current.stream().filter(task -> taskId.equals(task.get("id"))).findFirst()
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Task not found"));
        found.put("status", status);
        found.put("updatedAt", Instant.now().toString());
        save(workspace, attachments(workspace), current);
        return new LinkedHashMap<>(found);
    }

    public void deleteSession(Long userId, String sessionId) {
        requireSession(userId, sessionId);
        workspaces.deleteByUserIdAndSessionId(userId, sessionId);
    }

    // ----- storage -----

    private AgentWorkspace workspace(Long userId, String sessionId, boolean create) {
        requireSession(userId, sessionId);
        return workspaces.findByUserIdAndSessionId(userId, sessionId).orElseGet(() -> {
            if (!create) {
                AgentWorkspace empty = new AgentWorkspace();
                empty.setUserId(userId);
                empty.setSessionId(sessionId);
                empty.setAttachmentsJson("[]");
                empty.setTasksJson("[]");
                return empty;
            }
            AgentWorkspace fresh = new AgentWorkspace();
            fresh.setUserId(userId);
            fresh.setSessionId(sessionId);
            fresh.setRevision(0L);
            fresh.setNextSourceNumber(1);
            fresh.setAttachmentsJson("[]");
            fresh.setTasksJson("[]");
            return fresh;
        });
    }

    private void requireSession(Long userId, String sessionId) {
        sessions.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "No permission for this session"));
    }

    private void save(AgentWorkspace workspace, List<Map<String, Object>> attachments, List<Map<String, Object>> tasks) {
        workspace.setAttachmentsJson(write(attachments));
        workspace.setTasksJson(write(tasks));
        workspace.setRevision(workspace.getRevision() == null ? 1 : workspace.getRevision() + 1);
        workspaces.saveAndFlush(workspace);
    }

    private List<Map<String, Object>> attachments(AgentWorkspace workspace) {
        return read(workspace.getAttachmentsJson());
    }

    private List<Map<String, Object>> tasks(AgentWorkspace workspace) {
        return read(workspace.getTasksJson());
    }

    private Map<String, Object> attachment(AgentWorkspace workspace, String id) {
        return attachments(workspace).stream().filter(item -> id.equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Attachment not found"));
    }

    private Map<String, Object> patchArtifact(Long userId, String sessionId, String artifactId) {
        Map<String, Object> artifact = runs.findArtifact(userId, sessionId, artifactId);
        if (!"patch".equals(artifact.get("kind"))) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "This artifact is not a patch");
        }
        return artifact;
    }

    private static List<String> taskTitles(String content) {
        Object parsed;
        try {
            parsed = new ObjectMapper().readValue(content, Object.class);
        } catch (Exception ex) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The artifact is not valid JSON");
        }
        List<?> items = null;
        if (parsed instanceof Map<?, ?> map) {
            if (map.get("todos") instanceof List<?> todos) items = todos;
            else if (map.get("items") instanceof List<?> list) items = list;
        }
        if (items == null || items.isEmpty()) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The artifact carries no tasks");
        }
        List<String> titles = new ArrayList<>();
        for (Object item : items) {
            String title = item instanceof String text ? text : null;
            if (item instanceof Map<?, ?> map) {
                // `task` is this project's own todo field; `title` is what a plain list uses.
                for (String key : new String[]{"title", "task"}) {
                    if (map.get(key) instanceof String value && !value.isBlank()) {
                        title = value;
                        break;
                    }
                }
            }
            if (title == null || title.isBlank()) {
                throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Every task needs a title");
            }
            titles.add(title.trim());
        }
        return titles;
    }

    private static String checkUpload(MultipartFile file, String name) {
        if (file == null || name == null || name.isBlank()) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "A file is required");
        }
        if (name.length() > 200 || name.startsWith(".") || name.contains("/") || name.contains("\\")
                || name.contains("://") || name.matches("^[A-Za-z]:.*")) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The file name must be a plain name, not a path");
        }
        String suffix = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!TEXT_SUFFIXES.contains(suffix) && !DOCUMENT_SUFFIXES.contains(suffix)) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "This file type is not supported");
        }
        if (file.getSize() <= 0) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The file is empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The file exceeds the 5 MB limit");
        }
        return suffix;
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The upload could not be read");
        }
    }

    /** The first `budget` characters, never splitting a surrogate pair. */
    static String capCodePoints(String text, int budget) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        if (count <= budget) return text;
        return text.substring(0, text.offsetByCodePoints(0, budget));
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private List<Map<String, Object>> read(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<ArrayList<Map<String, Object>>>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("Stored workspace is unreadable", ex);
        }
    }

    private String write(List<Map<String, Object>> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not store the workspace", ex);
        }
    }
}
