package com.nexus.chat.controller;

import com.nexus.chat.dto.UserDTO;
import com.nexus.chat.service.FollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 关注系统 REST Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /**
     * 关注用户
     * POST /api/users/{id}/follow?followerId={followerId}
     */
    @PostMapping("/{id}/follow")
    public ResponseEntity<?> followUser(
            @PathVariable Long id,
            @RequestParam Long followerId) {
        try {
            followService.followUser(followerId, id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 取消关注
     * DELETE /api/users/{id}/follow?followerId={followerId}
     */
    @DeleteMapping("/{id}/follow")
    public ResponseEntity<?> unfollowUser(
            @PathVariable Long id,
            @RequestParam Long followerId) {
        try {
            followService.unfollowUser(followerId, id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 查询关注状态
     * GET /api/users/{id}/follow/status?followerId={followerId}
     */
    @GetMapping("/{id}/follow/status")
    public ResponseEntity<?> getFollowStatus(
            @PathVariable Long id,
            @RequestParam Long followerId) {
        boolean isFollowing = followService.isFollowing(followerId, id);
        return ResponseEntity.ok(Map.of("isFollowing", isFollowing));
    }

    /**
     * 获取关注列表
     * GET /api/users/{id}/following?page=0&size=20
     */
    @GetMapping("/{id}/following")
    public ResponseEntity<Page<UserDTO>> getFollowingList(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(followService.getFollowingList(id, page, size));
    }

    /**
     * 获取粉丝列表
     * GET /api/users/{id}/followers?page=0&size=20
     */
    @GetMapping("/{id}/followers")
    public ResponseEntity<Page<UserDTO>> getFollowerList(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(followService.getFollowerList(id, page, size));
    }
}
