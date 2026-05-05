package com.nexus.chat.service;

import com.nexus.chat.dto.*;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.User;
import com.nexus.chat.model.UserPrivacySettings;
import com.nexus.chat.model.UserSocialLink;
import com.nexus.chat.model.UserSecuritySettings;
import com.nexus.chat.model.UserActivity;
import com.nexus.chat.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserPrivacySettingsRepository privacySettingsRepository;
    private final ContactRepository contactRepository;
    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final UserSocialLinkRepository socialLinkRepository;
    private final UserSecuritySettingsRepository securitySettingsRepository;
    private final UserSessionRepository sessionRepository;
    private final UserActivityRepository activityRepository;
    private final UserFollowRepository userFollowRepository;
    private final PostRepository postRepository;

    // Avatar upload directory (can be configured)
    private static final String AVATAR_UPLOAD_DIR = "uploads/avatars/";

    public UserDTO getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        return mapToDTO(user);
    }

    public UserDTO getUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        return mapToDTO(user);
    }

    public List<UserDTO> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public UserDTO updateProfile(Long userId, String nickname, String avatarUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
        }

        User updated = userRepository.save(user);
        return mapToDTO(updated);
    }

    @Transactional
    public void updateOnlineStatus(Long userId, boolean isOnline) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        user.setIsOnline(isOnline);
        userRepository.save(user);
    }

    /**
     * Get full user profile with privacy settings
     */
    public UserProfileDTO getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        UserPrivacySettings privacy = privacySettingsRepository.findByUserId(userId)
                .orElse(createDefaultPrivacySettings(userId));

        UserProfileDTO profile = new UserProfileDTO();
        profile.setId(user.getId());
        profile.setUsername(user.getUsername());
        profile.setNickname(user.getNickname());
        profile.setEmail(user.getEmail());
        profile.setPhone(user.getPhone());
        profile.setAvatarUrl(user.getAvatarUrl());
        profile.setBio(user.getBio());
        profile.setProfileBackground(user.getProfileBackground());
        profile.setIsOnline(user.getIsOnline());
        profile.setLastSeen(user.getLastSeen());
        profile.setCreatedAt(user.getCreatedAt());

        // Privacy settings
        profile.setShowOnlineStatus(privacy.getShowOnlineStatus());
        profile.setShowLastSeen(privacy.getShowLastSeen());
        profile.setShowEmail(privacy.getShowEmail());
        profile.setShowPhone(privacy.getShowPhone());

        // Statistics
        profile.setContactCount((long) contactRepository.findByUserId(userId).size());
        profile.setGroupCount(chatRepository.countUserGroups(userId));
        profile.setMessageCount(messageRepository.countBySenderId(userId));

        // Social links
        profile.setSocialLinks(getSocialLinksMap(userId));

        // Security status
        UserSecuritySettings security = securitySettingsRepository.findByUserId(userId).orElse(null);
        profile.setTwoFactorEnabled(security != null && security.getTwoFactorEnabled());
        profile.setPasswordStrength(calculatePasswordStrength(security));
        profile.setActiveSessions((int) sessionRepository.countByUserId(userId));

        return profile;
    }

    /**
     * Get user profile for viewing by another user (respects privacy settings)
     */
    public UserProfileDTO getUserProfileForViewer(Long userId, Long viewerId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        UserPrivacySettings privacy = privacySettingsRepository.findByUserId(userId)
                .orElse(createDefaultPrivacySettings(userId));

        UserProfileDTO profile = new UserProfileDTO();
        profile.setId(user.getId());
        profile.setUsername(user.getUsername());
        profile.setNickname(user.getNickname());
        profile.setAvatarUrl(user.getAvatarUrl());
        profile.setBio(user.getBio());
        profile.setCreatedAt(user.getCreatedAt());

        // Apply privacy settings
        if (privacy.getShowOnlineStatus()) {
            profile.setIsOnline(user.getIsOnline());
        }
        if (privacy.getShowLastSeen()) {
            profile.setLastSeen(user.getLastSeen());
        }
        if (privacy.getShowEmail()) {
            profile.setEmail(user.getEmail());
        }
        if (privacy.getShowPhone()) {
            profile.setPhone(user.getPhone());
        }

        return profile;
    }

    /**
     * Update user profile
     */
    @Transactional
    public UserProfileDTO updateUserProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        if (request.getNickname() != null && !request.getNickname().isEmpty()) {
            if (request.getNickname().length() < 2 || request.getNickname().length() > 30) {
                throw new BusinessException("error.user.nickname.length");
            }
            user.setNickname(request.getNickname());
        }
        if (request.getBio() != null) {
            if (request.getBio().length() > 150) {
                throw new BusinessException("error.user.bio.length");
            }
            user.setBio(request.getBio());
        }
        if (request.getEmail() != null && !request.getEmail().isEmpty()) {
            // Check if email is already used by another user
            userRepository.findByEmail(request.getEmail())
                    .ifPresent(existingUser -> {
                        if (!existingUser.getId().equals(userId)) {
                            throw new BusinessException("error.user.email.in.use");
                        }
                    });
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }

        userRepository.save(user);
        return getUserProfile(userId);
    }

    /**
     * Upload user avatar
     */
    @Transactional
    public String uploadAvatar(Long userId, MultipartFile file) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        // Validate file
        if (file.isEmpty()) {
            throw new BusinessException("error.user.file.empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("error.user.file.image.only");
        }

        // Generate unique filename
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".png";
        String filename = "avatar_" + userId + "_" + UUID.randomUUID().toString() + extension;

        // Create upload directory if not exists
        Path uploadPath = Paths.get(AVATAR_UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // Save file
        Path filePath = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        // Update user avatar URL
        String avatarUrl = "/uploads/avatars/" + filename;
        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);

        return avatarUrl;
    }

    /**
     * Upload avatar as Base64
     */
    @Transactional
    public String uploadAvatarBase64(Long userId, String base64Image) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        // For simplicity, store Base64 directly (suitable for smaller images)
        // In production, consider decoding and saving as file
        user.setAvatarUrl(base64Image);
        userRepository.save(user);

        return base64Image;
    }

    /**
     * Delete user avatar
     */
    @Transactional
    public void deleteAvatar(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        // Delete old file if it's a path
        String oldAvatar = user.getAvatarUrl();
        if (oldAvatar != null && oldAvatar.startsWith("/uploads/")) {
            try {
                Path oldPath = Paths.get(oldAvatar.substring(1));
                Files.deleteIfExists(oldPath);
            } catch (IOException e) {
                // Ignore deletion errors
            }
        }

        user.setAvatarUrl(null);
        userRepository.save(user);
    }

    /**
     * Update privacy settings
     */
    @Transactional
    public PrivacySettingsDTO updatePrivacySettings(Long userId, PrivacySettingsDTO request) {
        UserPrivacySettings settings = privacySettingsRepository.findByUserId(userId)
                .orElse(createDefaultPrivacySettings(userId));

        if (request.getShowOnlineStatus() != null) {
            settings.setShowOnlineStatus(request.getShowOnlineStatus());
        }
        if (request.getShowLastSeen() != null) {
            settings.setShowLastSeen(request.getShowLastSeen());
        }
        if (request.getShowEmail() != null) {
            settings.setShowEmail(request.getShowEmail());
        }
        if (request.getShowPhone() != null) {
            settings.setShowPhone(request.getShowPhone());
        }
        if (request.getFriendRequestMode() != null) {
            try {
                settings.setFriendRequestMode(
                    UserPrivacySettings.FriendRequestMode.valueOf(request.getFriendRequestMode())
                );
            } catch (IllegalArgumentException e) {
                // 默认使用 DIRECT
                settings.setFriendRequestMode(UserPrivacySettings.FriendRequestMode.DIRECT);
            }
        }

        UserPrivacySettings saved = privacySettingsRepository.save(settings);

        PrivacySettingsDTO response = new PrivacySettingsDTO();
        response.setShowOnlineStatus(saved.getShowOnlineStatus());
        response.setShowLastSeen(saved.getShowLastSeen());
        response.setShowEmail(saved.getShowEmail());
        response.setShowPhone(saved.getShowPhone());
        response.setFriendRequestMode(saved.getFriendRequestMode().name());

        return response;
    }

    /**
     * Get user statistics
     */
    public UserStatsDTO getUserStats(Long userId) {
        long contactCount = contactRepository.findByUserId(userId).size();
        long groupCount = chatRepository.countUserGroups(userId);
        long messageCount = messageRepository.countBySenderId(userId);
        long followingCount = userFollowRepository.countByFollowerId(userId);
        long followerCount = userFollowRepository.countByFollowingId(userId);
        long postCount = postRepository.countByAuthorId(userId);

        return new UserStatsDTO(contactCount, groupCount, messageCount,
                followingCount, followerCount, postCount);
    }

    /**
     * Search users by query
     */
    public List<UserDTO> searchUsers(String query) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        List<User> users = userRepository.searchUsers(query.trim());
        return users.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get recommended users
     */
    public List<UserDTO> getRecommendedUsers(Long userId, int limit) {
        List<User> users = userRepository.findRecommendedUsers(userId, limit);
        return users.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Create default privacy settings for user (only if not exists)
     */
    private UserPrivacySettings createDefaultPrivacySettings(Long userId) {
        // Check if already exists to avoid duplicate entry error
        return privacySettingsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserPrivacySettings settings = new UserPrivacySettings();
                    settings.setUserId(userId);
                    settings.setShowOnlineStatus(true);
                    settings.setShowLastSeen(true);
                    settings.setShowEmail(false);
                    settings.setShowPhone(false);
                    return privacySettingsRepository.save(settings);
                });
    }

    private UserDTO mapToDTO(User user) {
        return new UserDTO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getIsOnline(),
                user.getLastSeen());
    }

    /**
     * Get social links as a map
     */
    private Map<String, String> getSocialLinksMap(Long userId) {
        List<UserSocialLink> links = socialLinkRepository.findByUserId(userId);
        Map<String, String> map = new HashMap<>();
        for (UserSocialLink link : links) {
            map.put(link.getPlatform(), link.getUrl());
        }
        return map;
    }

    /**
     * Calculate password strength (simplified)
     */
    private Integer calculatePasswordStrength(UserSecuritySettings security) {
        // Default strength, in production this would be calculated based on password policy
        return 60;
    }

    /**
     * Update profile background
     */
    @Transactional
    public String updateProfileBackground(Long userId, String background) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        user.setProfileBackground(background);
        userRepository.save(user);
        return background;
    }

    /**
     * Get all social links for a user
     */
    public List<SocialLinkDTO> getSocialLinks(Long userId) {
        return socialLinkRepository.findByUserId(userId).stream()
                .map(link -> new SocialLinkDTO(
                        link.getId(),
                        link.getPlatform(),
                        link.getUrl(),
                        link.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * Add or update a social link
     */
    @Transactional
    public SocialLinkDTO saveSocialLink(Long userId, String platform, String url) {
        UserSocialLink link = socialLinkRepository.findByUserIdAndPlatform(userId, platform)
                .orElse(new UserSocialLink());

        link.setUserId(userId);
        link.setPlatform(platform);
        link.setUrl(url);

        UserSocialLink saved = socialLinkRepository.save(link);
        return new SocialLinkDTO(saved.getId(), saved.getPlatform(), saved.getUrl(), saved.getCreatedAt());
    }

    /**
     * Delete a social link
     */
    @Transactional
    public void deleteSocialLink(Long userId, String platform) {
        socialLinkRepository.deleteByUserIdAndPlatform(userId, platform);
    }

    /**
     * Update all social links (replace all)
     */
    @Transactional
    public Map<String, String> updateSocialLinks(Long userId, Map<String, String> links) {
        // Delete all existing links
        socialLinkRepository.deleteByUserId(userId);

        // Add new links
        for (Map.Entry<String, String> entry : links.entrySet()) {
            UserSocialLink link = new UserSocialLink();
            link.setUserId(userId);
            link.setPlatform(entry.getKey());
            link.setUrl(entry.getValue());
            socialLinkRepository.save(link);
        }

        return links;
    }

    /**
     * Get user's recent activities
     */
    public List<ActivityDTO> getUserActivities(Long userId, int limit) {
        List<UserActivity> activities = activityRepository.findRecentByUserId(userId, limit);
        return activities.stream()
                .map(this::mapActivityToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get friend activities (activity feed)
     */
    public List<ActivityDTO> getFriendActivities(Long userId, int limit) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, limit);
        List<UserActivity> activities = activityRepository.findFriendActivities(userId, pageable);
        return activities.stream()
                .map(this::mapActivityToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Record user activity
     */
    @Transactional
    public void recordActivity(Long userId, UserActivity.ActivityType activityType, String description, Long relatedId) {
        UserActivity activity = new UserActivity();
        activity.setUserId(userId);
        activity.setActivityType(activityType);
        activity.setDescription(description);
        activity.setRelatedId(relatedId);
        activityRepository.save(activity);
    }

    /**
     * Map UserActivity to ActivityDTO
     */
    private ActivityDTO mapActivityToDTO(UserActivity activity) {
        ActivityDTO dto = new ActivityDTO();
        dto.setId(activity.getId());
        dto.setActivityType(activity.getActivityType().name());
        dto.setDescription(activity.getDescription());
        dto.setRelatedId(activity.getRelatedId());
        dto.setCreatedAt(activity.getCreatedAt());

        // Get user info
        userRepository.findById(activity.getUserId()).ifPresent(user -> {
            UserDTO userDTO = mapToDTO(user);
            dto.setUser(userDTO);
        });

        return dto;
    }

}
