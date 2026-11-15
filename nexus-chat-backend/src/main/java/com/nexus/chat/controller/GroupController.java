package com.nexus.chat.controller;

import com.nexus.chat.dto.*;
import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Group (Chat Group) management
 */
@Slf4j
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /**
     * Create a new group
     * POST /api/groups?userId={userId}
     */
    @PostMapping
    public ResponseEntity<GroupDTO> createGroup(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody CreateGroupRequest request) {
        Long userId = CurrentUser.require(claimedUserId);
        GroupDTO group = groupService.createGroup(userId, request);
        return ResponseEntity.ok(group);
    }

    /**
     * Get group by ID
     * GET /api/groups/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<GroupDTO> getGroupById(@PathVariable Long id) {
        groupService.requireMember(id, CurrentUser.require());
        GroupDTO group = groupService.getGroupById(id);
        return ResponseEntity.ok(group);
    }

    /**
     * Update group information
     * PUT /api/groups/{id}?userId={userId}
     */
    @PutMapping("/{id}")
    public ResponseEntity<GroupDTO> updateGroup(
            @PathVariable Long id,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody UpdateGroupRequest request) {
        Long userId = CurrentUser.require(claimedUserId);
        GroupDTO group = groupService.updateGroup(id, userId, request);
        return ResponseEntity.ok(group);
    }

    /**
     * Delete/disband group
     * DELETE /api/groups/{id}?userId={userId}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable Long id,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        groupService.deleteGroup(id, userId);
        return ResponseEntity.ok().build();
    }

    /**
     * Add members to group
     * POST /api/groups/{id}/members?userId={userId}
     */
    @PostMapping("/{id}/members")
    public ResponseEntity<Void> addMembers(
            @PathVariable Long id,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody AddMembersRequest request) {
        Long userId = CurrentUser.require(claimedUserId);
        groupService.addMembers(id, userId, request.getUserIds());
        return ResponseEntity.ok().build();
    }

    /**
     * Remove member from group
     * DELETE /api/groups/{id}/members/{memberId}?userId={userId}
     */
    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable Long id,
            @PathVariable Long memberId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        groupService.removeMember(id, userId, memberId);
        return ResponseEntity.ok().build();
    }

    /**
     * Leave group
     * POST /api/groups/{id}/leave?userId={userId}
     */
    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leaveGroup(
            @PathVariable Long id,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        groupService.leaveGroup(id, userId);
        return ResponseEntity.ok().build();
    }

    /**
     * Get group members
     * GET /api/groups/{id}/members
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<List<GroupMemberDTO>> getGroupMembers(@PathVariable Long id) {
        groupService.requireMember(id, CurrentUser.require());
        List<GroupMemberDTO> members = groupService.getGroupMembers(id);
        return ResponseEntity.ok(members);
    }

    /**
     * Set or remove admin role for a member
     * PUT /api/groups/{id}/members/{memberId}/admin?userId={operatorId}&isAdmin=true
     */
    @PutMapping("/{id}/members/{memberId}/admin")
    public ResponseEntity<Void> setAdmin(
            @PathVariable Long id,
            @PathVariable Long memberId,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestParam Boolean isAdmin) {
        Long userId = CurrentUser.require(claimedUserId);
        groupService.setAdmin(id, userId, memberId, isAdmin);
        return ResponseEntity.ok().build();
    }

    /**
     * Transfer group ownership to another member
     * POST /api/groups/{id}/transfer?userId={ownerId}&newOwnerId={newOwnerId}
     */
    @PostMapping("/{id}/transfer")
    public ResponseEntity<Void> transferOwnership(
            @PathVariable Long id,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestParam Long newOwnerId) {
        Long userId = CurrentUser.require(claimedUserId);
        groupService.transferOwnership(id, userId, newOwnerId);
        return ResponseEntity.ok().build();
    }

    /**
     * Get user's groups
     * GET /api/users/{userId}/groups
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<GroupDTO>> getUserGroups(@PathVariable Long userId) {
        CurrentUser.require(userId);
        List<GroupDTO> groups = groupService.getUserGroups(userId);
        return ResponseEntity.ok(groups);
    }

}
