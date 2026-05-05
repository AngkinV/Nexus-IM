package com.nexus.chat.service;

import com.nexus.chat.dto.*;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.Chat;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.Message;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Create a new group
     */
    @Transactional
    public GroupDTO createGroup(Long creatorId, CreateGroupRequest request) {
        // Create group chat
        Chat chat = new Chat();
        chat.setType(Chat.ChatType.group);
        chat.setName(request.getName());
        chat.setDescription(request.getDescription());
        chat.setAvatarUrl(request.getAvatar());
        chat.setIsPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : false);
        chat.setCreatedBy(creatorId);
        chat.setMemberCount(1); // Creator is the first member
        Chat savedChat = chatRepository.save(chat);

        // Add creator as owner
        ChatMember creatorMember = new ChatMember();
        creatorMember.setChatId(savedChat.getId());
        creatorMember.setUserId(creatorId);
        creatorMember.setRole(ChatMember.MemberRole.owner);
        creatorMember.setIsAdmin(true);
        chatMemberRepository.save(creatorMember);

        // Add other members
        if (request.getMemberIds() != null) {
            for (Long memberId : request.getMemberIds()) {
                if (!memberId.equals(creatorId)) {
                    ChatMember member = new ChatMember();
                    member.setChatId(savedChat.getId());
                    member.setUserId(memberId);
                    member.setRole(ChatMember.MemberRole.member);
                    member.setIsAdmin(false);
                    chatMemberRepository.save(member);
                }
            }
            // Update member count
            savedChat.setMemberCount(request.getMemberIds().size() + 1);
            chatRepository.save(savedChat);
        }

        GroupDTO groupDTO = mapToGroupDTO(savedChat);

        // Broadcast group creation
        broadcastGroupEvent("group:created", groupDTO, savedChat.getId());

        return groupDTO;
    }

    /**
     * Get group by ID
     */
    public GroupDTO getGroupById(Long groupId) {
        Chat chat = chatRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException("error.group.not.found"));

        if (chat.getType() != Chat.ChatType.group) {
            throw new BusinessException("error.group.not.group.chat");
        }

        return mapToGroupDTO(chat);
    }

    /**
     * Update group information
     */
    @Transactional
    public GroupDTO updateGroup(Long groupId, Long userId, UpdateGroupRequest request) {
        Chat chat = chatRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException("error.group.not.found"));

        // Check if user is admin
        ChatMember member = chatMemberRepository.findByChatIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException("error.chat.not.member"));

        if (!member.getIsAdmin()) {
            throw new BusinessException("error.group.admin.required");
        }

        // Update fields
        if (request.getName() != null) {
            chat.setName(request.getName());
        }
        if (request.getDescription() != null) {
            chat.setDescription(request.getDescription());
        }
        if (request.getAvatar() != null) {
            chat.setAvatarUrl(request.getAvatar());
        }
        if (request.getIsPrivate() != null) {
            chat.setIsPrivate(request.getIsPrivate());
        }

        Chat updatedChat = chatRepository.save(chat);
        GroupDTO groupDTO = mapToGroupDTO(updatedChat);

        // Broadcast group update
        broadcastGroupEvent("group:updated", groupDTO, groupId);

        return groupDTO;
    }

    /**
     * Delete/disband group
     */
    @Transactional
    public void deleteGroup(Long groupId, Long userId) {
        Chat chat = chatRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException("error.group.not.found"));

        // Only owner can delete group
        if (!chat.getCreatedBy().equals(userId)) {
            throw new BusinessException("error.group.owner.required");
        }

        // Broadcast deletion before deleting
        broadcastGroupEvent("group:deleted", Map.of("groupId", groupId), groupId);

        // Delete all members first
        chatMemberRepository.deleteByChatId(groupId);

        // Delete group
        chatRepository.delete(chat);
    }

    /**
     * Add members to group
     */
    @Transactional
    public void addMembers(Long groupId, Long userId, List<Long> userIds) {
        Chat chat = chatRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException("error.group.not.found"));

        // Check if user is admin
        ChatMember adminMember = chatMemberRepository.findByChatIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException("error.chat.not.member"));

        if (!adminMember.getIsAdmin()) {
            throw new BusinessException("error.group.admin.add.member");
        }

        for (Long newUserId : userIds) {
            if (!chatMemberRepository.existsByChatIdAndUserId(groupId, newUserId)) {
                // Verify user exists
                User user = userRepository.findById(newUserId)
                        .orElseThrow(() -> new BusinessException("error.user.not.found"));

                ChatMember member = new ChatMember();
                member.setChatId(groupId);
                member.setUserId(newUserId);
                member.setRole(ChatMember.MemberRole.member);
                member.setIsAdmin(false);
                chatMemberRepository.save(member);
            }
        }

        // Update member count
        int newMemberCount = (int) chatMemberRepository.countByChatId(groupId);
        chat.setMemberCount(newMemberCount);
        chatRepository.save(chat);

        // Broadcast member joined for each new member
        for (Long newUserId : userIds) {
            User user = userRepository.findById(newUserId).orElse(null);
            if (user != null) {
                UserDTO userDTO = mapToUserDTO(user);
                broadcastGroupEvent("group:member-joined",
                        Map.of("groupId", groupId, "member", userDTO, "memberCount", newMemberCount), groupId);
            }
        }
    }

    /**
     * Remove member from group
     */
    @Transactional
    public void removeMember(Long groupId, Long adminUserId, Long memberUserId) {
        Chat chat = chatRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException("error.group.not.found"));

        // Check if requesting user is admin
        ChatMember adminMember = chatMemberRepository.findByChatIdAndUserId(groupId, adminUserId)
                .orElseThrow(() -> new BusinessException("error.chat.not.member"));

        if (!adminMember.getIsAdmin()) {
            throw new BusinessException("error.group.admin.remove.member");
        }

        // Can't remove owner
        if (chat.getCreatedBy().equals(memberUserId)) {
            throw new BusinessException("error.group.cannot.remove.owner");
        }

        chatMemberRepository.deleteByChatIdAndUserId(groupId, memberUserId);

        // Update member count
        int newMemberCount = (int) chatMemberRepository.countByChatId(groupId);
        chat.setMemberCount(newMemberCount);
        chatRepository.save(chat);

        // Broadcast member removed with updated member count
        broadcastGroupEvent("group:member-left",
                Map.of("groupId", groupId, "memberId", memberUserId, "memberCount", newMemberCount), groupId);
    }

    /**
     * Leave group
     */
    @Transactional
    public void leaveGroup(Long groupId, Long userId) {
        Chat chat = chatRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException("error.group.not.found"));

        // Verify user is a member
        ChatMember member = chatMemberRepository.findByChatIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException("error.chat.not.member"));

        // Owner cannot leave (must delete group)
        if (chat.getCreatedBy().equals(userId)) {
            throw new BusinessException("error.group.owner.cannot.leave");
        }

        chatMemberRepository.deleteByChatIdAndUserId(groupId, userId);

        // Update member count
        int newMemberCount = (int) chatMemberRepository.countByChatId(groupId);
        chat.setMemberCount(newMemberCount);
        chatRepository.save(chat);

        // Broadcast member left with updated member count
        broadcastGroupEvent("group:member-left",
                Map.of("groupId", groupId, "memberId", userId, "memberCount", newMemberCount), groupId);
    }

    /**
     * Get group members with role information
     */
    public List<GroupMemberDTO> getGroupMembers(Long groupId) {
        List<ChatMember> members = chatMemberRepository.findByChatId(groupId);
        return members.stream()
                .map(member -> {
                    User user = userRepository.findById(member.getUserId()).orElse(null);
                    if (user != null) {
                        return mapToGroupMemberDTO(user, member);
                    }
                    return null;
                })
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }

    /**
     * Set or remove admin role for a member
     */
    @Transactional
    public void setAdmin(Long groupId, Long operatorId, Long targetUserId, Boolean isAdmin) {
        Chat chat = chatRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException("error.group.not.found"));

        // Only owner can set/remove admin
        if (!chat.getCreatedBy().equals(operatorId)) {
            throw new BusinessException("error.group.owner.required");
        }

        // Cannot change owner's admin status
        if (chat.getCreatedBy().equals(targetUserId)) {
            throw new BusinessException("error.group.cannot.change.owner.admin");
        }

        ChatMember targetMember = chatMemberRepository.findByChatIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new BusinessException("error.chat.not.member"));

        targetMember.setIsAdmin(isAdmin);
        targetMember.setRole(isAdmin ? ChatMember.MemberRole.admin : ChatMember.MemberRole.member);
        chatMemberRepository.save(targetMember);

        // Get user info for broadcast
        User user = userRepository.findById(targetUserId).orElse(null);
        String nickname = user != null ? user.getNickname() : "Unknown";

        // Broadcast admin change
        broadcastGroupEvent("group:admin-changed",
                Map.of("groupId", groupId, "memberId", targetUserId, "nickname", nickname, "isAdmin", isAdmin), groupId);
    }

    /**
     * Transfer group ownership to another member
     */
    @Transactional
    public void transferOwnership(Long groupId, Long ownerId, Long newOwnerId) {
        Chat chat = chatRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException("error.group.not.found"));

        // Only current owner can transfer
        if (!chat.getCreatedBy().equals(ownerId)) {
            throw new BusinessException("error.group.owner.required");
        }

        // Cannot transfer to self
        if (ownerId.equals(newOwnerId)) {
            throw new BusinessException("error.group.cannot.transfer.to.self");
        }

        // Verify new owner is a member
        ChatMember newOwnerMember = chatMemberRepository.findByChatIdAndUserId(groupId, newOwnerId)
                .orElseThrow(() -> new BusinessException("error.chat.not.member"));

        // Get current owner member
        ChatMember currentOwnerMember = chatMemberRepository.findByChatIdAndUserId(groupId, ownerId)
                .orElseThrow(() -> new BusinessException("error.chat.not.member"));

        // Update current owner to admin
        currentOwnerMember.setRole(ChatMember.MemberRole.admin);
        currentOwnerMember.setIsAdmin(true);
        chatMemberRepository.save(currentOwnerMember);

        // Update new owner
        newOwnerMember.setRole(ChatMember.MemberRole.owner);
        newOwnerMember.setIsAdmin(true);
        chatMemberRepository.save(newOwnerMember);

        // Update chat's createdBy
        chat.setCreatedBy(newOwnerId);
        chatRepository.save(chat);

        // Get user info for broadcast
        User newOwner = userRepository.findById(newOwnerId).orElse(null);
        String newOwnerNickname = newOwner != null ? newOwner.getNickname() : "Unknown";

        // Broadcast ownership transfer
        broadcastGroupEvent("group:ownership-transferred",
                Map.of("groupId", groupId, "oldOwnerId", ownerId, "newOwnerId", newOwnerId, "newOwnerNickname", newOwnerNickname), groupId);
    }

    /**
     * Get groups for a user
     */
    public List<GroupDTO> getUserGroups(Long userId) {
        List<Chat> groups = chatRepository.findUserGroups(userId);
        return groups.stream()
                .map(this::mapToGroupDTO)
                .collect(Collectors.toList());
    }

    /**
     * Map Chat to GroupDTO
     */
    private GroupDTO mapToGroupDTO(Chat chat) {
        GroupDTO dto = new GroupDTO();
        dto.setId(chat.getId());
        dto.setName(chat.getName());
        dto.setDescription(chat.getDescription());
        dto.setAvatar(chat.getAvatarUrl());
        dto.setType(chat.getType());
        dto.setIsPrivate(chat.getIsPrivate());
        dto.setCreatorId(chat.getCreatedBy());
        dto.setMemberCount(chat.getMemberCount());
        dto.setCreatedAt(chat.getCreatedAt());

        // Get members
        List<GroupMemberDTO> members = getGroupMembers(chat.getId());
        dto.setMembers(members);

        // Get last message
        List<Message> messages = messageRepository.findByChatIdOrderByCreatedAtDesc(chat.getId());
        if (!messages.isEmpty()) {
            Message lastMsg = messages.get(0);
            dto.setLastMessage(lastMsg.getContent());
            dto.setLastMessageTime(lastMsg.getCreatedAt());
        }

        return dto;
    }

    /**
     * Map User to UserDTO
     */
    private UserDTO mapToUserDTO(User user) {
        return new UserDTO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getIsOnline(),
                user.getLastSeen());
    }

    /**
     * Map User and ChatMember to GroupMemberDTO
     */
    private GroupMemberDTO mapToGroupMemberDTO(User user, ChatMember member) {
        return new GroupMemberDTO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getIsOnline(),
                member.getRole().name(),
                member.getIsAdmin(),
                member.getJoinedAt(),
                user.getLastSeen());
    }

    /**
     * Broadcast group event to all members
     */
    private void broadcastGroupEvent(String eventType, Object payload, Long groupId) {
        WebSocketMessage wsMessage = new WebSocketMessage(
                WebSocketMessage.MessageType.valueOf(eventType.replace(":", "_").replace("-", "_").toUpperCase()),
                payload);
        messagingTemplate.convertAndSend("/topic/group/" + groupId, wsMessage);
    }

}
