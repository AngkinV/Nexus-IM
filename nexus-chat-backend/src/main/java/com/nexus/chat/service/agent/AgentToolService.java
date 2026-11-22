package com.nexus.chat.service.agent;

import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.Chat;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.Message;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.service.agent.tool.Actor;
import com.nexus.chat.service.agent.tool.AgentTool;
import com.nexus.chat.service.agent.tool.AgentToolException;
import com.nexus.chat.service.agent.tool.AgentToolProvider;
import com.nexus.chat.service.agent.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only tools the Python agent can call over MCP. To add a tool, add one
 * {@link AgentTool} method here (or on another {@link AgentToolProvider} bean).
 *
 * Every tool re-authorizes against the {@link Actor}: the actor comes from the
 * request header, never from model-supplied arguments.
 */
@Service
@RequiredArgsConstructor
public class AgentToolService implements AgentToolProvider {

    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final UserRepository userRepository;

    @AgentTool(name = "get_recent_messages",
            description = "Fetch the most recent messages of the given chat. Use this to ground summaries / todos / replies in real chat content.")
    public Map<String, Object> getRecentMessages(
            @Actor Long actorUserId,
            @ToolParam(name = "chat_id", description = "Chat ID to read") Long chatId,
            @ToolParam(name = "limit", description = "How many messages, max 200. Default 80.", required = false) Integer limit
    ) {
        if (limit != null && limit < 1) {
            throw AgentToolException.badArg("limit must be at least 1");
        }
        ensureMember(actorUserId, chatId);
        List<Map<String, Object>> messages = messageRepository
                .findByChatIdOrderByCreatedAtDesc(chatId, PageRequest.of(0, limit == null ? 80 : Math.min(limit, 200)))
                .getContent()
                .stream()
                .map(m -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("messageId", m.getId());
                    item.put("senderId", m.getSenderId());
                    item.put("senderName", senderName(m.getSenderId()));
                    item.put("content", m.getContent());
                    item.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
                    return item;
                })
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatId", chatId);
        body.put("messages", messages);
        return body;
    }

    @AgentTool(name = "get_chat_profile",
            description = "Fetch chat metadata: name, type (direct/group), member ids.")
    public Map<String, Object> getChatProfile(
            @Actor Long actorUserId,
            @ToolParam(name = "chat_id") Long chatId
    ) {
        ensureMember(actorUserId, chatId);
        Chat chat = chatRepository.findById(chatId).orElseThrow(() -> AgentToolException.notFound("chat not found"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatId", chatId);
        body.put("chatType", chat.getType().name().toLowerCase());
        body.put("chatName", chat.getName() == null ? "" : chat.getName());
        body.put("memberIds", chatMemberRepository.findByChatId(chatId).stream().map(ChatMember::getUserId).toList());
        return body;
    }

    @AgentTool(name = "get_user_profile",
            description = "Fetch a user's display profile. Useful for mentioning correct nicknames.")
    public Map<String, Object> getUserProfile(
            @Actor Long actorUserId,
            @ToolParam(name = "user_id") Long userId
    ) {
        ensureActorExists(actorUserId);
        return profile(userRepository.findById(userId).orElseThrow(() -> AgentToolException.notFound("user not found")));
    }

    @AgentTool(name = "get_message_by_id",
            description = "Fetch a single message by id (used for reply suggestion to inspect target message).")
    public Map<String, Object> getMessageById(
            @Actor Long actorUserId,
            @ToolParam(name = "message_id") Long messageId
    ) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> AgentToolException.notFound("message not found"));
        ensureMember(actorUserId, message.getChatId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", message.getId());
        body.put("chatId", message.getChatId());
        body.put("senderId", message.getSenderId());
        body.put("senderName", senderName(message.getSenderId()));
        body.put("content", message.getContent());
        body.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
        return body;
    }

    @AgentTool(name = "find_user_by_username",
            description = "Resolve a user's profile from their @username handle. "
                    + "Use this WHENEVER the user mentions someone by an @handle (e.g. '@test00001', "
                    + "'帮我看一下 @bob 的资料'). The returned payload includes the numeric userId, "
                    + "which can then be passed to other tools like get_user_profile or used in answers.")
    public Map<String, Object> findUserByUsername(
            @Actor Long actorUserId,
            @ToolParam(name = "username",
                    description = "The username handle, with or without a leading '@'. e.g. 'test00001' or '@test00001'.")
            String username
    ) {
        ensureActorExists(actorUserId);
        return profile(userByHandle(username));
    }

    @AgentTool(name = "list_my_chats",
            description = "List the actor's own chats (1-on-1 and group), most-recent first. "
                    + "Use this when the user refers to a chat by name — e.g. '总结一下「项目A推进群」' "
                    + "or '我和谁在聊' — and you don't yet know the numeric chatId. The optional "
                    + "`query` does case-insensitive substring matching on the chat name (for direct "
                    + "chats the name is the other person's @handle).")
    public Map<String, Object> listMyChats(
            @Actor Long actorUserId,
            @ToolParam(name = "query", description = "Optional fuzzy match on chat name. Empty = list all.", required = false)
            String query,
            @ToolParam(name = "type", description = "Optional filter; omit to include both.", required = false,
                    enumValues = {"direct", "group"})
            String type,
            @ToolParam(name = "limit", description = "Max rows to return, 1..50. Default 20.", required = false)
            Integer limit
    ) {
        ensureActorExists(actorUserId);
        String q = query == null ? "" : query.trim().toLowerCase();

        List<Map<String, Object>> rows = chatRepository.findByUserIdOrderByLastMessageAtDesc(actorUserId).stream()
                .filter(c -> type == null || c.getType().name().equals(type))
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("chatId", c.getId());
                    row.put("type", c.getType().name().toLowerCase());
                    String groupName = c.getName() == null ? "" : c.getName();
                    // Direct chats are shown under the other member's handle.
                    row.put("name", c.getType() != Chat.ChatType.direct ? groupName
                            : chatMemberRepository.findByChatId(c.getId()).stream()
                                    .filter(cm -> !actorUserId.equals(cm.getUserId()))
                                    .findFirst()
                                    .flatMap(cm -> userRepository.findById(cm.getUserId()))
                                    .map(u -> "@" + u.getUsername())
                                    .orElse(groupName));
                    row.put("lastMessageAt", c.getLastMessageAt() == null ? null : c.getLastMessageAt().toString());
                    return row;
                })
                .filter(row -> q.isEmpty() || ((String) row.get("name")).toLowerCase().contains(q))
                .limit(limit == null ? 20 : Math.max(1, Math.min(limit, 50)))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chats", rows);
        return body;
    }

    @AgentTool(name = "find_direct_chat_with_user",
            description = "Find the 1-on-1 (direct) chat between the actor and a named user. "
                    + "Use this when the user says things like '我和 @test00001 的聊天', '总结我和 bob 的对话'. "
                    + "Returns the chatId, then call get_recent_messages with that chatId. "
                    + "If no direct chat exists, this returns an error — tell the user there isn't one.")
    public Map<String, Object> findDirectChatWithUser(
            @Actor Long actorUserId,
            @ToolParam(name = "username", description = "Other party's @handle, with or without leading '@'.")
            String username
    ) {
        User other = userByHandle(username);
        // The query is scoped to the actor, so only the actor's own direct chat can match.
        Chat chat = chatRepository.findDirectChatBetweenUsers(actorUserId, other.getId())
                .orElseThrow(() -> AgentToolException.notFound("no direct chat with @" + other.getUsername()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatId", chat.getId());
        body.put("type", chat.getType().name().toLowerCase());
        body.put("otherUserId", other.getId());
        body.put("otherUsername", other.getUsername());
        return body;
    }

    private void ensureMember(Long userId, Long chatId) {
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "No permission for this chat", Map.of("chatId", chatId));
        }
    }

    private void ensureActorExists(Long actorUserId) {
        if (!userRepository.existsById(actorUserId)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "Actor user not found");
        }
    }

    private User userByHandle(String username) {
        String trimmed = username.trim();
        String handle = trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
        if (handle.isBlank()) {
            throw AgentToolException.badArg("username is blank");
        }
        return userRepository.findByUsername(handle)
                .orElseThrow(() -> AgentToolException.notFound("user not found: @" + handle));
    }

    private String senderName(Long senderId) {
        return userRepository.findById(senderId).map(User::getUsername).orElse("unknown");
    }

    private static Map<String, Object> profile(User user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.getId());
        body.put("username", user.getUsername());
        body.put("nickname", user.getUsername());
        body.put("timezone", "Asia/Shanghai");
        return body;
    }
}
