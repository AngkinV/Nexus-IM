package com.nexus.chat.controller;

import com.nexus.chat.dto.*;
import com.nexus.chat.model.Contact;
import com.nexus.chat.repository.ContactRepository;
import com.nexus.chat.service.PresenceService;
import com.nexus.chat.service.RedisCacheService;
import com.nexus.chat.service.RedisMessageRelay;
import com.nexus.chat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for User management
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ContactRepository contactRepository;
    private final PresenceService presenceService;
    private final RedisCacheService redisCacheService;
    private final RedisMessageRelay redisMessageRelay;

    /**
     * Get user by ID
     * GET /api/users/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserDTO> getUserById(@PathVariable Long id) {
        try {
            UserDTO user = userService.getUserById(id);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get user by username
     * GET /api/users/username/{username}
     */
    @GetMapping("/username/{username}")
    public ResponseEntity<UserDTO> getUserByUsername(@PathVariable String username) {
        try {
            UserDTO user = userService.getUserByUsername(username);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all users
     * GET /api/users
     */
    @GetMapping
    public ResponseEntity<List<UserDTO>> getAllUsers() {
        List<UserDTO> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    /**
     * Search users by query (username, nickname, or email)
     * GET /api/users/search?query={query}
     */
    @GetMapping("/search")
    public ResponseEntity<List<UserDTO>> searchUsers(@RequestParam String query) {
        List<UserDTO> users = userService.searchUsers(query);
        return ResponseEntity.ok(users);
    }

    /**
     * Get recommended users (for adding contacts)
     * GET /api/users/recommended?userId={userId}&limit={limit}
     */
    @GetMapping("/recommended")
    public ResponseEntity<List<UserDTO>> getRecommendedUsers(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "10") int limit) {
        List<UserDTO> users = userService.getRecommendedUsers(userId, limit);
        return ResponseEntity.ok(users);
    }

    /**
     * Get user profile (with privacy settings and stats)
     * GET /api/users/{id}/profile
     */
    @GetMapping("/{id}/profile")
    public ResponseEntity<UserProfileDTO> getUserProfile(@PathVariable Long id) {
        try {
            UserProfileDTO profile = userService.getUserProfile(id);
            return ResponseEntity.ok(profile);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get user profile for viewer (respects privacy settings)
     * GET /api/users/{id}/profile?viewerId={viewerId}
     */
    @GetMapping("/{id}/profile/view")
    public ResponseEntity<UserProfileDTO> getUserProfileForViewer(
            @PathVariable Long id,
            @RequestParam Long viewerId) {
        try {
            UserProfileDTO profile = userService.getUserProfileForViewer(id, viewerId);
            return ResponseEntity.ok(profile);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Update user profile (legacy endpoint)
     * PUT /api/users/{id}/profile
     */
    @PutMapping("/{id}/profile")
    public ResponseEntity<UserProfileDTO> updateProfile(
            @PathVariable Long id,
            @RequestBody UpdateProfileRequest request) {
        try {
            UserProfileDTO updated = userService.updateUserProfile(id, request);

            // Broadcast nickname update to all contacts
            if (request.getNickname() != null && !request.getNickname().isEmpty()) {
                notifyProfileUpdate(id, updated.getAvatarUrl(), request.getNickname());
            }

            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Upload avatar as file
     * POST /api/users/{id}/avatar
     */
    @PostMapping("/{id}/avatar")
    public ResponseEntity<Map<String, String>> uploadAvatar(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        try {
            String avatarUrl = userService.uploadAvatar(id, file);

            // Broadcast avatar update to all contacts
            notifyProfileUpdate(id, avatarUrl, null);

            return ResponseEntity.ok(Map.of("avatarUrl", avatarUrl));
        } catch (IOException | RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Upload avatar as Base64
     * POST /api/users/{id}/avatar/base64
     */
    @PostMapping("/{id}/avatar/base64")
    public ResponseEntity<Map<String, String>> uploadAvatarBase64(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            String base64Image = request.get("avatar");
            if (base64Image == null || base64Image.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            String avatarUrl = userService.uploadAvatarBase64(id, base64Image);

            // Broadcast avatar update to all contacts
            notifyProfileUpdate(id, avatarUrl, null);

            return ResponseEntity.ok(Map.of("avatarUrl", avatarUrl));
        } catch (IOException | RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete avatar
     * DELETE /api/users/{id}/avatar
     */
    @DeleteMapping("/{id}/avatar")
    public ResponseEntity<Void> deleteAvatar(@PathVariable Long id) {
        try {
            userService.deleteAvatar(id);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update privacy settings
     * PUT /api/users/{id}/privacy
     */
    @PutMapping("/{id}/privacy")
    public ResponseEntity<PrivacySettingsDTO> updatePrivacySettings(
            @PathVariable Long id,
            @RequestBody PrivacySettingsDTO request) {
        try {
            PrivacySettingsDTO settings = userService.updatePrivacySettings(id, request);
            return ResponseEntity.ok(settings);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get user statistics
     * GET /api/users/{id}/stats
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<UserStatsDTO> getUserStats(@PathVariable Long id) {
        try {
            UserStatsDTO stats = userService.getUserStats(id);
            return ResponseEntity.ok(stats);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Update online status
     * PUT /api/users/{id}/status
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<Void> updateOnlineStatus(
            @PathVariable Long id,
            @RequestParam boolean isOnline) {
        userService.updateOnlineStatus(id, isOnline);
        return ResponseEntity.ok().build();
    }

    /**
     * Update profile background
     * PUT /api/users/{id}/background
     */
    @PutMapping("/{id}/background")
    public ResponseEntity<Map<String, String>> updateBackground(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            String background = request.get("background");
            if (background == null || background.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            String updated = userService.updateProfileBackground(id, background);
            return ResponseEntity.ok(Map.of("profileBackground", updated));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get user's social links
     * GET /api/users/{id}/social-links
     */
    @GetMapping("/{id}/social-links")
    public ResponseEntity<List<SocialLinkDTO>> getSocialLinks(@PathVariable Long id) {
        try {
            List<SocialLinkDTO> links = userService.getSocialLinks(id);
            return ResponseEntity.ok(links);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Add or update a social link
     * POST /api/users/{id}/social-links
     */
    @PostMapping("/{id}/social-links")
    public ResponseEntity<SocialLinkDTO> addSocialLink(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            String platform = request.get("platform");
            String url = request.get("url");
            if (platform == null || url == null || platform.isEmpty() || url.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            SocialLinkDTO link = userService.saveSocialLink(id, platform, url);
            return ResponseEntity.ok(link);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a social link
     * DELETE /api/users/{id}/social-links/{platform}
     */
    @DeleteMapping("/{id}/social-links/{platform}")
    public ResponseEntity<Void> deleteSocialLink(
            @PathVariable Long id,
            @PathVariable String platform) {
        try {
            userService.deleteSocialLink(id, platform);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Batch update social links (replace all)
     * PUT /api/users/{id}/social-links
     */
    @PutMapping("/{id}/social-links")
    public ResponseEntity<Map<String, String>> updateSocialLinks(
            @PathVariable Long id,
            @RequestBody Map<String, String> links) {
        try {
            Map<String, String> updated = userService.updateSocialLinks(id, links);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get user's recent activities
     * GET /api/users/{id}/activities
     */
    @GetMapping("/{id}/activities")
    public ResponseEntity<List<ActivityDTO>> getUserActivities(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<ActivityDTO> activities = userService.getUserActivities(id, limit);
            return ResponseEntity.ok(activities);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get friend activities (activity feed)
     * GET /api/users/{id}/friend-activities
     */
    @GetMapping("/{id}/friend-activities")
    public ResponseEntity<List<ActivityDTO>> getFriendActivities(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<ActivityDTO> activities = userService.getFriendActivities(id, limit);
            return ResponseEntity.ok(activities);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Notify all contacts about user profile update (avatar/nickname)
     */
    private void notifyProfileUpdate(Long userId, String avatarUrl, String nickname) {
        try {
            // Get all users who have this user as a contact
            List<Contact> contacts = contactRepository.findByContactUserId(userId);

            if (contacts.isEmpty()) {
                log.debug("No contacts to notify for user {}", userId);
                return;
            }

            // Build notification payload
            Map<String, Object> payload = Map.of(
                    "userId", userId,
                    "avatarUrl", avatarUrl != null ? avatarUrl : "",
                    "nickname", nickname != null ? nickname : ""
            );

            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.USER_PROFILE_UPDATED,
                    payload
            );

            // Notify each contact
            for (Contact contact : contacts) {
                Long contactUserId = contact.getUserId();
                if (presenceService.isUserOnline(contactUserId)) {
                    String destination = "/topic/user." + contactUserId + ".messages";
                    redisMessageRelay.sendToUser(contactUserId, destination, wsMessage);
                } else {
                    // Queue for offline users
                    redisCacheService.queueOfflineMessage(contactUserId, wsMessage);
                }
            }

            log.info("Profile update notification sent to {} contacts for user {}", contacts.size(), userId);
        } catch (Exception e) {
            log.error("Failed to notify profile update for user {}: {}", userId, e.getMessage());
        }
    }

}
