package com.nexus.chat.service;

import com.nexus.chat.dto.ContactDTO;
import com.nexus.chat.dto.ContactRequestDTO;
import com.nexus.chat.dto.UserDTO;
import com.nexus.chat.dto.WebSocketMessage;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.Contact;
import com.nexus.chat.model.ContactRequest;
import com.nexus.chat.model.ContactRequest.RequestStatus;
import com.nexus.chat.model.User;
import com.nexus.chat.model.UserPrivacySettings;
import com.nexus.chat.model.UserPrivacySettings.FriendRequestMode;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.ContactRepository;
import com.nexus.chat.repository.ContactRequestRepository;
import com.nexus.chat.repository.UserPrivacySettingsRepository;
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
public class ContactService {

    private final ContactRepository contactRepository;
    private final ContactRequestRepository contactRequestRepository;
    private final UserRepository userRepository;
    private final UserPrivacySettingsRepository privacySettingsRepository;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Add a contact - 根据目标用户的隐私设置决定是直接添加还是发送申请
     * @return ContactDTO if direct add, null if request sent
     */
    @Transactional
    public Object addContact(Long userId, Long contactUserId, String message) {
        // Can't add yourself
        if (userId.equals(contactUserId)) {
            throw new BusinessException("error.contact.self.add");
        }

        // Check if contact already exists
        if (contactRepository.existsByUserIdAndContactUserId(userId, contactUserId)) {
            throw new BusinessException("error.contact.exists");
        }

        // Check if contact user exists
        User contactUser = userRepository.findById(contactUserId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        // Get target user's privacy settings
        UserPrivacySettings privacy = privacySettingsRepository.findByUserId(contactUserId)
                .orElse(null);

        // Default to VERIFICATION mode (require approval)
        FriendRequestMode mode = (privacy != null && privacy.getFriendRequestMode() != null)
                ? privacy.getFriendRequestMode()
                : FriendRequestMode.VERIFY;

        if (mode == FriendRequestMode.DIRECT) {
            // 直接添加模式
            return directAddContact(userId, contactUserId, contactUser);
        } else {
            // 验证模式 - 发送好友申请
            return sendContactRequest(userId, contactUserId, message);
        }
    }

    /**
     * Add a contact (without message - for backwards compatibility)
     */
    @Transactional
    public Object addContact(Long userId, Long contactUserId) {
        return addContact(userId, contactUserId, null);
    }

    /**
     * 直接添加联系人
     */
    private ContactDTO directAddContact(Long userId, Long contactUserId, User contactUser) {
        Contact contact = new Contact();
        contact.setUserId(userId);
        contact.setContactUserId(contactUserId);
        Contact savedContact = contactRepository.save(contact);

        // Build contact DTO
        ContactDTO contactDTO = mapToContactDTO(savedContact, contactUser);

        // Notify user via WebSocket
        WebSocketMessage wsMessage = new WebSocketMessage(
                WebSocketMessage.MessageType.CONTACT_ADDED,
                contactDTO);
        messagingTemplate.convertAndSend("/topic/user." + userId + ".contacts", wsMessage);

        return contactDTO;
    }

    /**
     * 发送好友申请
     */
    @Transactional
    public ContactRequestDTO sendContactRequest(Long fromUserId, Long toUserId, String message) {
        // Check if request already exists
        if (contactRequestRepository.existsByFromUserIdAndToUserIdAndStatus(
                fromUserId, toUserId, RequestStatus.PENDING)) {
            throw new BusinessException("error.friend.request.already.sent");
        }

        // Check if already contacts
        if (contactRepository.existsByUserIdAndContactUserId(fromUserId, toUserId)) {
            throw new BusinessException("error.friend.request.already.contact");
        }

        User fromUser = userRepository.findById(fromUserId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        User toUser = userRepository.findById(toUserId)
                .orElseThrow(() -> new BusinessException("error.friend.request.target.not.found"));

        // Create the request
        ContactRequest request = new ContactRequest();
        request.setFromUserId(fromUserId);
        request.setToUserId(toUserId);
        request.setMessage(message);
        request.setStatus(RequestStatus.PENDING);

        ContactRequest saved = contactRequestRepository.save(request);

        // Build DTO
        ContactRequestDTO dto = mapToContactRequestDTO(saved, fromUser, toUser);

        // Notify target user via WebSocket
        WebSocketMessage wsMessage = new WebSocketMessage(
                WebSocketMessage.MessageType.CONTACT_REQUEST,
                dto);
        messagingTemplate.convertAndSend("/topic/user." + toUserId + ".contacts", wsMessage);

        return dto;
    }

    /**
     * 接受好友申请
     */
    @Transactional
    public ContactDTO acceptContactRequest(Long requestId, Long userId) {
        ContactRequest request = contactRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException("error.friend.request.not.found"));

        if (!request.getToUserId().equals(userId)) {
            throw new BusinessException("error.friend.request.not.authorized.accept");
        }

        if (request.getStatus() != RequestStatus.PENDING) {
            throw new BusinessException("error.friend.request.already.processed");
        }

        // Update request status
        request.setStatus(RequestStatus.ACCEPTED);
        contactRequestRepository.save(request);

        // Add contact for both users (双向添加)
        User fromUser = userRepository.findById(request.getFromUserId())
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        User toUser = userRepository.findById(request.getToUserId())
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        // Add to userId's contact list
        Contact contact1 = new Contact();
        contact1.setUserId(request.getToUserId());
        contact1.setContactUserId(request.getFromUserId());
        contactRepository.save(contact1);

        // Add to fromUserId's contact list
        Contact contact2 = new Contact();
        contact2.setUserId(request.getFromUserId());
        contact2.setContactUserId(request.getToUserId());
        contactRepository.save(contact2);

        // Build DTOs for WebSocket notifications
        ContactDTO contactDTOForTo = mapToContactDTO(contact1, fromUser);
        ContactDTO contactDTOForFrom = mapToContactDTO(contact2, toUser);

        // Notify both users
        WebSocketMessage wsMessageTo = new WebSocketMessage(
                WebSocketMessage.MessageType.CONTACT_ADDED,
                contactDTOForTo);
        messagingTemplate.convertAndSend("/topic/user." + request.getToUserId() + ".contacts", wsMessageTo);

        WebSocketMessage wsMessageFrom = new WebSocketMessage(
                WebSocketMessage.MessageType.CONTACT_REQUEST_ACCEPTED,
                contactDTOForFrom);
        messagingTemplate.convertAndSend("/topic/user." + request.getFromUserId() + ".contacts", wsMessageFrom);

        return contactDTOForTo;
    }

    /**
     * 拒绝好友申请
     */
    @Transactional
    public void rejectContactRequest(Long requestId, Long userId) {
        ContactRequest request = contactRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException("error.friend.request.not.found"));

        if (!request.getToUserId().equals(userId)) {
            throw new BusinessException("error.friend.request.not.authorized.reject");
        }

        if (request.getStatus() != RequestStatus.PENDING) {
            throw new BusinessException("error.friend.request.already.processed");
        }

        // Update request status
        request.setStatus(RequestStatus.REJECTED);
        contactRequestRepository.save(request);

        // Notify the requester
        WebSocketMessage wsMessage = new WebSocketMessage(
                WebSocketMessage.MessageType.CONTACT_REQUEST_REJECTED,
                Map.of("requestId", requestId, "toUserId", userId));
        messagingTemplate.convertAndSend("/topic/user." + request.getFromUserId() + ".contacts", wsMessage);
    }

    /**
     * 获取用户收到的待处理好友申请
     */
    public List<ContactRequestDTO> getPendingRequests(Long userId) {
        List<ContactRequest> requests = contactRequestRepository
                .findByToUserIdAndStatusOrderByCreatedAtDesc(userId, RequestStatus.PENDING);

        return requests.stream()
                .map(request -> {
                    User fromUser = userRepository.findById(request.getFromUserId()).orElse(null);
                    User toUser = userRepository.findById(request.getToUserId()).orElse(null);
                    return mapToContactRequestDTO(request, fromUser, toUser);
                })
                .filter(dto -> dto != null)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户发出的所有好友申请（包括所有状态）
     */
    public List<ContactRequestDTO> getSentRequests(Long userId) {
        List<ContactRequest> requests = contactRequestRepository
                .findByFromUserIdOrderByCreatedAtDesc(userId);

        return requests.stream()
                .map(request -> {
                    User fromUser = userRepository.findById(request.getFromUserId()).orElse(null);
                    User toUser = userRepository.findById(request.getToUserId()).orElse(null);
                    return mapToContactRequestDTO(request, fromUser, toUser);
                })
                .filter(dto -> dto != null)
                .collect(Collectors.toList());
    }

    /**
     * 获取待处理申请数量
     */
    public long getPendingRequestCount(Long userId) {
        return contactRequestRepository.countByToUserIdAndStatus(userId, RequestStatus.PENDING);
    }

    /**
     * Map ContactRequest to DTO
     */
    private ContactRequestDTO mapToContactRequestDTO(ContactRequest request, User fromUser, User toUser) {
        if (fromUser == null || toUser == null) return null;

        ContactRequestDTO dto = new ContactRequestDTO();
        dto.setId(request.getId());
        dto.setFromUserId(request.getFromUserId());
        dto.setFromUsername(fromUser.getUsername());
        dto.setFromNickname(fromUser.getNickname());
        dto.setFromAvatarUrl(fromUser.getAvatarUrl());
        dto.setFromIsOnline(fromUser.getIsOnline());
        dto.setToUserId(request.getToUserId());
        dto.setToUsername(toUser.getUsername());
        dto.setToNickname(toUser.getNickname());
        dto.setToAvatarUrl(toUser.getAvatarUrl());
        dto.setMessage(request.getMessage());
        dto.setStatus(request.getStatus().name());
        dto.setCreatedAt(request.getCreatedAt());
        dto.setUpdatedAt(request.getUpdatedAt());
        return dto;
    }

    /**
     * Remove a contact (双向删除，同时禁用私聊)
     */
    @Transactional
    public void removeContact(Long userId, Long contactUserId) {
        // 删除当前用户的联系人记录
        Contact contact = contactRepository.findByUserIdAndContactUserId(userId, contactUserId)
                .orElseThrow(() -> new BusinessException("error.contact.not.found"));
        contactRepository.delete(contact);

        // 也删除对方的联系人记录
        contactRepository.findByUserIdAndContactUserId(contactUserId, userId)
                .ifPresent(contactRepository::delete);

        // 删除双方之间的好友申请记录（允许将来重新添加好友）
        contactRequestRepository.deleteByFromUserIdAndToUserId(userId, contactUserId);
        contactRequestRepository.deleteByFromUserIdAndToUserId(contactUserId, userId);

        // 查找双方的私聊并禁用
        chatRepository.findDirectChatBetweenUsers(userId, contactUserId)
                .ifPresent(chat -> {
                    Long chatId = chat.getId();

                    // 删除双方的 ChatMember 记录
                    chatMemberRepository.deleteByChatIdAndUserId(chatId, userId);
                    chatMemberRepository.deleteByChatIdAndUserId(chatId, contactUserId);

                    // 通知双方聊天已禁用
                    WebSocketMessage chatDisabledMsg1 = new WebSocketMessage(
                            WebSocketMessage.MessageType.CHAT_DISABLED,
                            Map.of("chatId", chatId, "reason", "contact_removed"));
                    messagingTemplate.convertAndSend("/topic/user." + userId + ".contacts", chatDisabledMsg1);

                    WebSocketMessage chatDisabledMsg2 = new WebSocketMessage(
                            WebSocketMessage.MessageType.CHAT_DISABLED,
                            Map.of("chatId", chatId, "reason", "contact_removed"));
                    messagingTemplate.convertAndSend("/topic/user." + contactUserId + ".contacts", chatDisabledMsg2);
                });

        // 通知删除方
        WebSocketMessage wsMessage1 = new WebSocketMessage(
                WebSocketMessage.MessageType.CONTACT_REMOVED,
                Map.of("contactId", contactUserId));
        messagingTemplate.convertAndSend("/topic/user." + userId + ".contacts", wsMessage1);

        // 通知被删除方
        WebSocketMessage wsMessage2 = new WebSocketMessage(
                WebSocketMessage.MessageType.CONTACT_REMOVED,
                Map.of("contactId", userId));
        messagingTemplate.convertAndSend("/topic/user." + contactUserId + ".contacts", wsMessage2);
    }

    /**
     * Get contacts list with user details.
     * Batch-loads all users in 2 queries instead of N+1.
     */
    public List<UserDTO> getContacts(Long userId) {
        List<Contact> contacts = contactRepository.findByUserId(userId);
        if (contacts.isEmpty()) {
            return List.of();
        }

        // Batch load all contact users in 1 query
        List<Long> contactUserIds = contacts.stream()
                .map(Contact::getContactUserId)
                .collect(Collectors.toList());
        Map<Long, User> usersById = userRepository.findAllByIdIn(contactUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return contacts.stream()
                .map(contact -> {
                    User user = usersById.get(contact.getContactUserId());
                    if (user != null) {
                        return new UserDTO(
                                user.getId(),
                                user.getUsername(),
                                user.getNickname(),
                                user.getAvatarUrl(),
                                user.getIsOnline(),
                                user.getLastSeen());
                    }
                    return null;
                })
                .filter(u -> u != null)
                .collect(Collectors.toList());
    }

    /**
     * Get contacts list with detailed information (respecting privacy settings).
     * Optimized: Batch-loads all data in 3 queries instead of N+1.
     * Before: 1 + N queries (1 for contacts, N for privacy settings)
     * After: 3 queries total (contacts, users, privacy settings)
     */
    public List<ContactDTO> getContactsDetailed(Long userId) {
        List<Contact> contacts = contactRepository.findByUserId(userId);
        if (contacts.isEmpty()) {
            return List.of();
        }

        // Batch load all contact users in 1 query
        List<Long> contactUserIds = contacts.stream()
                .map(Contact::getContactUserId)
                .collect(Collectors.toList());
        Map<Long, User> usersById = userRepository.findAllByIdIn(contactUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // Batch load all privacy settings in 1 query (eliminates N+1)
        Map<Long, UserPrivacySettings> privacyByUserId = privacySettingsRepository
                .findByUserIdIn(contactUserIds).stream()
                .collect(Collectors.toMap(UserPrivacySettings::getUserId, p -> p));

        return contacts.stream()
                .map(contact -> {
                    User user = usersById.get(contact.getContactUserId());
                    if (user != null) {
                        UserPrivacySettings privacy = privacyByUserId.get(user.getId());
                        return mapToContactDTOWithPrivacy(contact, user, privacy);
                    }
                    return null;
                })
                .filter(c -> c != null)
                .collect(Collectors.toList());
    }

    /**
     * Check if a user is a contact
     */
    public boolean isContact(Long userId, Long contactUserId) {
        return contactRepository.existsByUserIdAndContactUserId(userId, contactUserId);
    }

    /**
     * Get mutual contacts between two users
     */
    public List<UserDTO> getMutualContacts(Long userId1, Long userId2) {
        List<Contact> user1Contacts = contactRepository.findByUserId(userId1);
        List<Contact> user2Contacts = contactRepository.findByUserId(userId2);

        List<Long> user1ContactIds = user1Contacts.stream()
                .map(Contact::getContactUserId)
                .collect(Collectors.toList());

        return user2Contacts.stream()
                .filter(c -> user1ContactIds.contains(c.getContactUserId()))
                .map(contact -> {
                    User user = userRepository.findById(contact.getContactUserId()).orElse(null);
                    if (user != null) {
                        return new UserDTO(
                                user.getId(),
                                user.getUsername(),
                                user.getNickname(),
                                user.getAvatarUrl(),
                                user.getIsOnline(),
                                user.getLastSeen());
                    }
                    return null;
                })
                .filter(u -> u != null)
                .collect(Collectors.toList());
    }

    /**
     * Notify contacts about user status change.
     * Uses reverse lookup index instead of full table scan.
     * Before: findAll() loaded entire contacts table + in-memory filter
     * After: findByContactUserId() uses idx_contacts_contact_user index
     */
    public void notifyContactsOfStatusChange(Long userId, boolean isOnline) {
        // Indexed reverse lookup: find users who have this user as a contact
        List<Contact> reverseContacts = contactRepository.findByContactUserId(userId);

        WebSocketMessage wsMessage = new WebSocketMessage(
                WebSocketMessage.MessageType.CONTACT_STATUS_CHANGED,
                Map.of("userId", userId, "isOnline", isOnline));

        for (Contact contact : reverseContacts) {
            messagingTemplate.convertAndSend("/topic/user." + contact.getUserId() + ".contacts", wsMessage);
        }
    }

    /**
     * Map Contact and User to ContactDTO
     */
    private ContactDTO mapToContactDTO(Contact contact, User user) {
        return new ContactDTO(
                contact.getId(),
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                user.getAvatarUrl(),
                user.getIsOnline(),
                user.getLastSeen(),
                contact.getCreatedAt());
    }

    /**
     * Map Contact and User to ContactDTO with privacy settings applied.
     * Accepts pre-loaded privacy settings to avoid N+1 queries.
     */
    private ContactDTO mapToContactDTOWithPrivacy(Contact contact, User user, UserPrivacySettings privacy) {
        ContactDTO dto = new ContactDTO();
        dto.setId(contact.getId());
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setNickname(user.getNickname());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setAddedAt(contact.getCreatedAt());

        // Apply privacy settings
        if (privacy == null || privacy.getShowOnlineStatus()) {
            dto.setIsOnline(user.getIsOnline());
        }
        if (privacy == null || privacy.getShowLastSeen()) {
            dto.setLastSeen(user.getLastSeen());
        }
        if (privacy != null && privacy.getShowEmail()) {
            dto.setEmail(user.getEmail());
        }
        if (privacy != null && privacy.getShowPhone()) {
            dto.setPhone(user.getPhone());
        }

        return dto;
    }

}
