package com.nexus.chat.service;

import com.nexus.chat.dto.UserDTO;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.User;
import com.nexus.chat.model.UserFollow;
import com.nexus.chat.repository.UserFollowRepository;
import com.nexus.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关注服务 - 处理用户关注/取消关注逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserRepository userRepository;

    /**
     * 关注用户
     */
    @Transactional
    public void followUser(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw new BusinessException("error.follow.self");
        }

        userRepository.findById(followerId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        userRepository.findById(followingId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        if (userFollowRepository.existsByFollowerIdAndFollowingId(followerId, followingId)) {
            throw new BusinessException("error.follow.already");
        }

        UserFollow follow = new UserFollow();
        follow.setFollowerId(followerId);
        follow.setFollowingId(followingId);
        userFollowRepository.save(follow);

        log.info("User {} followed user {}", followerId, followingId);
    }

    /**
     * 取消关注
     */
    @Transactional
    public void unfollowUser(Long followerId, Long followingId) {
        userFollowRepository.deleteByFollowerIdAndFollowingId(followerId, followingId);
        log.info("User {} unfollowed user {}", followerId, followingId);
    }

    /**
     * 检查是否已关注
     */
    public boolean isFollowing(Long followerId, Long followingId) {
        return userFollowRepository.existsByFollowerIdAndFollowingId(followerId, followingId);
    }

    /**
     * 获取关注数
     */
    public long getFollowingCount(Long userId) {
        return userFollowRepository.countByFollowerId(userId);
    }

    /**
     * 获取粉丝数
     */
    public long getFollowerCount(Long userId) {
        return userFollowRepository.countByFollowingId(userId);
    }

    /**
     * 获取关注列表（分页）
     */
    public Page<UserDTO> getFollowingList(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<UserFollow> follows = userFollowRepository.findByFollowerIdOrderByCreatedAtDesc(userId, pageable);
        return follows.map(f -> {
            User user = userRepository.findById(f.getFollowingId()).orElse(null);
            if (user == null) return null;
            return new UserDTO(user.getId(), user.getUsername(), user.getNickname(),
                    user.getAvatarUrl(), user.getIsOnline(), user.getLastSeen());
        });
    }

    /**
     * 获取粉丝列表（分页）
     */
    public Page<UserDTO> getFollowerList(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<UserFollow> follows = userFollowRepository.findByFollowingIdOrderByCreatedAtDesc(userId, pageable);
        return follows.map(f -> {
            User user = userRepository.findById(f.getFollowerId()).orElse(null);
            if (user == null) return null;
            return new UserDTO(user.getId(), user.getUsername(), user.getNickname(),
                    user.getAvatarUrl(), user.getIsOnline(), user.getLastSeen());
        });
    }
}
