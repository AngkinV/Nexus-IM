package com.nexus.chat.service;

import com.nexus.chat.dto.*;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.User;
import com.nexus.chat.model.UserPrivacySettings;
import com.nexus.chat.model.UserSocialLink;
import com.nexus.chat.model.UserSecuritySettings;
import com.nexus.chat.model.UserActivity;
import com.nexus.chat.repository.*;
import com.nexus.chat.storage.UploadStorage;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final PresenceService presenceService;
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
    private final RedisCacheService redisCacheService;
    private final UploadStorage uploadStorage;

    /** Avatars live under the uploads directory and, unlike attachments, never expire. */
    private static final String AVATAR_DIR = "avatars/";

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
        presenceService.refreshPresence(userId); // Compatibility query, not a client-authored status write.
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
        profile.setIsOnline(presenceService.getVisibleStatus(user.getId()));
        profile.setLastSeen(presenceService.getVisibleLastSeen(user.getId()));
        profile.setCreatedAt(user.getCreatedAt());

        // Privacy settings
        profile.setShowOnlineStatus(Boolean.TRUE.equals(privacy.getShowOnlineStatus()));
        profile.setShowLastSeen(Boolean.TRUE.equals(privacy.getShowLastSeen()));
        profile.setShowEmail(privacy.getShowEmail());
        profile.setShowPhone(privacy.getShowPhone());
        profile.setFriendRequestMode(privacy.getFriendRequestMode() == null
                ? UserPrivacySettings.FriendRequestMode.VERIFY.name()
                : privacy.getFriendRequestMode().name());

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
        if (Boolean.TRUE.equals(privacy.getShowOnlineStatus())) {
            profile.setIsOnline(presenceService.getVisibleStatus(user.getId()));
        }
        if (Boolean.TRUE.equals(privacy.getShowLastSeen())) {
            profile.setLastSeen(presenceService.getVisibleLastSeen(user.getId()));
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

    /** Decoded size limit for one avatar image. */
    private static final int MAX_AVATAR_BYTES = 5 * 1024 * 1024;
    /** Raster types only: an SVG served from our own origin can carry script. */
    private static final Map<String, String> AVATAR_TYPES = Map.of(
            "image/png", ".png", "image/jpeg", ".jpg", "image/jpg", ".jpg", "image/gif", ".gif", "image/webp", ".webp");
    private static final java.util.regex.Pattern AVATAR_DATA_URI = java.util.regex.Pattern.compile(
            "^data:(image/[a-z0-9.+-]+);base64,(.*)$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /**
     * Upload user avatar
     */
    @Transactional
    public String uploadAvatar(Long userId, MultipartFile file) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        if (file.isEmpty()) {
            throw new BusinessException("error.user.file.empty");
        }
        return storeAvatar(user, file.getContentType(), file.getBytes());
    }

    /**
     * Upload avatar as Base64.
     *
     * The image is written to the uploads directory and only its URL is saved. Keeping
     * the data URI in the user row would send the whole picture with every contact,
     * group member and message that names this user.
     */
    @Transactional
    public String uploadAvatarBase64(Long userId, String base64Image) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        java.util.regex.Matcher dataUri = AVATAR_DATA_URI.matcher(base64Image == null ? "" : base64Image.trim());
        if (!dataUri.matches()) {
            throw new BusinessException("error.user.file.image.only");
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getMimeDecoder().decode(dataUri.group(2));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("error.user.file.image.only");
        }
        return storeAvatar(user, dataUri.group(1), bytes);
    }

    private String storeAvatar(User user, String contentType, byte[] bytes) throws IOException {
        String extension = contentType == null ? null : AVATAR_TYPES.get(contentType.toLowerCase(java.util.Locale.ROOT));
        if (extension == null) {
            throw new BusinessException("error.user.file.image.only");
        }
        if (bytes.length == 0) {
            throw new BusinessException("error.user.file.empty");
        }
        if (bytes.length > MAX_AVATAR_BYTES) {
            throw new BusinessException("error.user.file.too.large");
        }
        String filename = "avatar_" + user.getId() + "_" + UUID.randomUUID() + extension;
        Path target = uploadStorage.resolve(AVATAR_DIR + filename);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);

        String previous = user.getAvatarUrl();
        String avatarUrl = UploadStorage.URL_PREFIX + AVATAR_DIR + filename;
        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);
        deleteOwnAvatarFile(previous);
        return avatarUrl;
    }

    /**
     * Delete user avatar
     */
    @Transactional
    public void deleteAvatar(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        String previous = user.getAvatarUrl();
        user.setAvatarUrl(null);
        userRepository.save(user);
        deleteOwnAvatarFile(previous);
    }

    /**
     * Remove a replaced avatar file. Only files this service wrote (uploads/avatars/) are
     * touched: an avatar that came through the attachment upload has its own record and
     * lifetime, and a URL or data URI names no file of ours at all.
     */
    private void deleteOwnAvatarFile(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(UploadStorage.URL_PREFIX + AVATAR_DIR)) return;
        Path file = uploadStorage.fileOf(avatarUrl);
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove the replaced avatar file {}", file.getFileName());
        }
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
            // An unknown value is rejected (-> 400). It used to be stored as DIRECT,
            // i.e. a typo silently switched the account to the *less* private mode.
            settings.setFriendRequestMode(
                UserPrivacySettings.FriendRequestMode.valueOf(request.getFriendRequestMode().trim())
            );
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

        // Day-over-day windows [start, end)
        LocalDate today = LocalDate.now();
        LocalDateTime startToday = today.atStartOfDay();
        LocalDateTime startTomorrow = today.plusDays(1).atStartOfDay();
        LocalDateTime startYesterday = today.minusDays(1).atStartOfDay();

        long messagesToday = messageRepository.countBySenderIdAndCreatedAtBetween(userId, startToday, startTomorrow);
        long messagesYesterday = messageRepository.countBySenderIdAndCreatedAtBetween(userId, startYesterday, startToday);
        long contactsToday = contactRepository.countByUserIdAndCreatedAtBetween(userId, startToday, startTomorrow);
        long contactsYesterday = contactRepository.countByUserIdAndCreatedAtBetween(userId, startYesterday, startToday);
        long groupsToday = chatRepository.countUserGroupsJoinedBetween(userId, startToday, startTomorrow);
        long groupsYesterday = chatRepository.countUserGroupsJoinedBetween(userId, startYesterday, startToday);

        long onlineSecToday = redisCacheService.getOnlineSecondsForDate(userId, today);
        long onlineSecYesterday = redisCacheService.getOnlineSecondsForDate(userId, today.minusDays(1));
        // Cumulative online time, 1-decimal hours
        double onlineHours = Math.round(redisCacheService.getTotalOnlineSeconds(userId) / 360.0) / 10.0;

        UserStatsDTO dto = new UserStatsDTO();
        dto.setContactCount(contactCount);
        dto.setGroupCount(groupCount);
        dto.setMessageCount(messageCount);
        dto.setFollowingCount(followingCount);
        dto.setFollowerCount(followerCount);
        dto.setPostCount(postCount);
        dto.setOnlineHours(onlineHours);
        dto.setMessagesDelta(pctDelta(messagesToday, messagesYesterday));
        dto.setContactsDelta(pctDelta(contactsToday, contactsYesterday));
        dto.setGroupsDelta(pctDelta(groupsToday, groupsYesterday));
        dto.setOnlineDelta(pctDelta(onlineSecToday, onlineSecYesterday));
        return dto;
    }

    /**
     * Day-over-day percent change. Positive = up, negative = down, 0 = flat.
     * With no activity yesterday, any activity today reads as +100%.
     */
    private Integer pctDelta(long today, long yesterday) {
        if (yesterday == 0) {
            return today > 0 ? 100 : 0;
        }
        long pct = Math.round((today - yesterday) * 100.0 / yesterday);
        if (pct > 999) pct = 999;
        if (pct < -999) pct = -999;
        return (int) pct;
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
                presenceService.getVisibleStatus(user.getId()),
                presenceService.getVisibleLastSeen(user.getId()));
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
     * Password strength as cached on the security settings at register /
     * change-password time. 0 when the user has never had a score recorded.
     */
    private Integer calculatePasswordStrength(UserSecuritySettings security) {
        if (security != null && security.getPasswordStrength() != null) {
            return security.getPasswordStrength();
        }
        return 0;
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
