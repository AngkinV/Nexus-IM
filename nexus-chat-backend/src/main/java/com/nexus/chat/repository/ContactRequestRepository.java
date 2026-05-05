package com.nexus.chat.repository;

import com.nexus.chat.model.ContactRequest;
import com.nexus.chat.model.ContactRequest.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContactRequestRepository extends JpaRepository<ContactRequest, Long> {

    /**
     * 查找用户收到的所有好友申请
     */
    List<ContactRequest> findByToUserIdOrderByCreatedAtDesc(Long toUserId);

    /**
     * 查找用户收到的待处理好友申请
     */
    List<ContactRequest> findByToUserIdAndStatusOrderByCreatedAtDesc(Long toUserId, RequestStatus status);

    /**
     * 查找用户发出的所有好友申请
     */
    List<ContactRequest> findByFromUserIdOrderByCreatedAtDesc(Long fromUserId);

    /**
     * 查找用户发出的待处理好友申请
     */
    List<ContactRequest> findByFromUserIdAndStatusOrderByCreatedAtDesc(Long fromUserId, RequestStatus status);

    /**
     * 查找特定的好友申请
     */
    Optional<ContactRequest> findByFromUserIdAndToUserId(Long fromUserId, Long toUserId);

    /**
     * 检查是否存在待处理的好友申请
     */
    boolean existsByFromUserIdAndToUserIdAndStatus(Long fromUserId, Long toUserId, RequestStatus status);

    /**
     * 检查两个用户之间是否存在任何好友申请
     */
    boolean existsByFromUserIdAndToUserId(Long fromUserId, Long toUserId);

    /**
     * 统计用户待处理的好友申请数量
     */
    long countByToUserIdAndStatus(Long toUserId, RequestStatus status);

    /**
     * 删除两个用户之间的好友申请
     */
    void deleteByFromUserIdAndToUserId(Long fromUserId, Long toUserId);
}
