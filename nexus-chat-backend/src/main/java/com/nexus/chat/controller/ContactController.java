package com.nexus.chat.controller;

import com.nexus.chat.dto.AddContactRequest;
import com.nexus.chat.dto.ContactDTO;
import com.nexus.chat.dto.ContactRequestDTO;
import com.nexus.chat.dto.UserDTO;
import com.nexus.chat.service.ContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for Contact management
 */
@Slf4j
@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    /**
     * Add a contact (or send friend request based on target user's privacy settings)
     * POST /api/contacts
     * Returns:
     * - type: "direct" with contact data if directly added
     * - type: "request" with request data if friend request was sent
     */
    @PostMapping
    public ResponseEntity<?> addContact(@RequestBody AddContactRequest request) {
        try {
            Object result = contactService.addContact(
                    request.getUserId(),
                    request.getContactUserId(),
                    request.getMessage()
            );

            if (result instanceof ContactDTO) {
                return ResponseEntity.ok(Map.of(
                        "type", "direct",
                        "data", result
                ));
            } else if (result instanceof ContactRequestDTO) {
                return ResponseEntity.ok(Map.of(
                        "type", "request",
                        "data", result
                ));
            }
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Remove a contact
     * DELETE /api/contacts
     */
    @DeleteMapping
    public ResponseEntity<Void> removeContact(@RequestBody AddContactRequest request) {
        try {
            contactService.removeContact(request.getUserId(), request.getContactUserId());
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get contacts list (basic info)
     * GET /api/contacts/user/{userId}
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<UserDTO>> getContacts(@PathVariable Long userId) {
        List<UserDTO> contacts = contactService.getContacts(userId);
        return ResponseEntity.ok(contacts);
    }

    /**
     * Get contacts list with detailed info (respects privacy)
     * GET /api/contacts/user/{userId}/detailed
     */
    @GetMapping("/user/{userId}/detailed")
    public ResponseEntity<List<ContactDTO>> getContactsDetailed(@PathVariable Long userId) {
        List<ContactDTO> contacts = contactService.getContactsDetailed(userId);
        return ResponseEntity.ok(contacts);
    }

    /**
     * Check if user is a contact
     * GET /api/contacts/check?userId={userId}&contactUserId={contactUserId}
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Boolean>> isContact(
            @RequestParam Long userId,
            @RequestParam Long contactUserId) {
        boolean isContact = contactService.isContact(userId, contactUserId);
        return ResponseEntity.ok(Map.of("isContact", isContact));
    }

    /**
     * Get mutual contacts between two users
     * GET /api/contacts/mutual?userId1={userId1}&userId2={userId2}
     */
    @GetMapping("/mutual")
    public ResponseEntity<List<UserDTO>> getMutualContacts(
            @RequestParam Long userId1,
            @RequestParam Long userId2) {
        List<UserDTO> mutualContacts = contactService.getMutualContacts(userId1, userId2);
        return ResponseEntity.ok(mutualContacts);
    }

    // ==================== 好友申请相关接口 ====================

    /**
     * Get pending friend requests for a user
     * GET /api/contacts/requests/pending/{userId}
     */
    @GetMapping("/requests/pending/{userId}")
    public ResponseEntity<List<ContactRequestDTO>> getPendingRequests(@PathVariable Long userId) {
        List<ContactRequestDTO> requests = contactService.getPendingRequests(userId);
        return ResponseEntity.ok(requests);
    }

    /**
     * Get sent friend requests for a user
     * GET /api/contacts/requests/sent/{userId}
     */
    @GetMapping("/requests/sent/{userId}")
    public ResponseEntity<List<ContactRequestDTO>> getSentRequests(@PathVariable Long userId) {
        List<ContactRequestDTO> requests = contactService.getSentRequests(userId);
        return ResponseEntity.ok(requests);
    }

    /**
     * Get pending request count
     * GET /api/contacts/requests/count/{userId}
     */
    @GetMapping("/requests/count/{userId}")
    public ResponseEntity<Map<String, Long>> getPendingRequestCount(@PathVariable Long userId) {
        long count = contactService.getPendingRequestCount(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Accept a friend request
     * POST /api/contacts/requests/{requestId}/accept?userId={userId}
     */
    @PostMapping("/requests/{requestId}/accept")
    public ResponseEntity<?> acceptRequest(
            @PathVariable Long requestId,
            @RequestParam Long userId) {
        try {
            ContactDTO contact = contactService.acceptContactRequest(requestId, userId);
            return ResponseEntity.ok(contact);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reject a friend request
     * POST /api/contacts/requests/{requestId}/reject?userId={userId}
     */
    @PostMapping("/requests/{requestId}/reject")
    public ResponseEntity<?> rejectRequest(
            @PathVariable Long requestId,
            @RequestParam Long userId) {
        try {
            contactService.rejectContactRequest(requestId, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

}
