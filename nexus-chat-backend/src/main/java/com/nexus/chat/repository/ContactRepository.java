package com.nexus.chat.repository;

import com.nexus.chat.model.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {

    List<Contact> findByUserId(Long userId);

    /**
     * Count contacts a user added within a time window (for day-over-day stats)
     */
    long countByUserIdAndCreatedAtBetween(Long userId, LocalDateTime start, LocalDateTime end);

    Optional<Contact> findByUserIdAndContactUserId(Long userId, Long contactUserId);

    boolean existsByUserIdAndContactUserId(Long userId, Long contactUserId);

    void deleteByUserIdAndContactUserId(Long userId, Long contactUserId);

    /**
     * Reverse lookup: find all users who have the given user as a contact.
     * Used for efficient status change notifications (replaces findAll + filter).
     */
    List<Contact> findByContactUserId(Long contactUserId);

}
