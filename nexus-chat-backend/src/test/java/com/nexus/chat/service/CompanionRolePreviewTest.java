package com.nexus.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.CompanionRoleDTO;
import com.nexus.chat.model.CompanionConversation;
import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionMessage.SenderType;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.CompanionStatus;
import com.nexus.chat.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** What a seat-list row says about its conversation, with the database mocked. */
@ExtendWith(MockitoExtension.class)
class CompanionRolePreviewTest {

    private static final long USER = 7L;
    private static final long IRIS = 3L;
    private static final long NOOR = 4L;
    private static final LocalDateTime SPOKEN_AT = LocalDateTime.of(2026, 11, 22, 21, 30);

    @Mock CompanionRoleRepository roleRepository;
    @Mock CompanionGrowthRepository growthRepository;
    @Mock CompanionMemoryRepository memoryRepository;
    @Mock CompanionConversationRepository conversationRepository;
    @Mock CompanionMessageRepository messageRepository;
    @Mock CompanionModelBindingRepository modelBindingRepository;
    @Mock ModelCredentialRepository credentialRepository;
    @Mock CompanionStatusRepository statusRepository;
    @Mock CompanionModelService modelService;
    @Mock CompanionCryptoService cryptoService;
    @Mock TavernSessionRepository sessionRepository;
    @Mock CompanionModelResolver modelResolver;

    private CompanionService service;

    @BeforeEach
    void setUp() {
        service = new CompanionService(roleRepository, growthRepository, memoryRepository,
                conversationRepository, messageRepository, modelBindingRepository, credentialRepository,
                statusRepository, new CompanionFallbackService(), modelService, cryptoService,
                sessionRepository, modelResolver);
    }

    @Test
    void theListReadsEachRowsLineAndSenderOffTheSameNewestMessageInOneQuery() {
        CompanionRole iris = role(IRIS, "艾瑞斯");
        iris.setBackground("守着这间酒馆的人");
        iris.setGreeting("（她抬起头。）你来了。");
        when(roleRepository.findByUserIdOrderByIdAsc(USER)).thenReturn(List.of(iris, role(NOOR, "诺尔")));
        when(conversationRepository.findByUserId(USER)).thenReturn(List.of(conversation(IRIS)));
        when(statusRepository.findByUserId(USER)).thenReturn(List.of(status(IRIS, "把杯子往前推了推")));
        // Not in seat order: a row finds its message by character, never by position.
        when(messageRepository.findLatestPerRole(USER)).thenReturn(List.of(
                message(NOOR, SenderType.user, "那明天见"),
                message(IRIS, SenderType.role, "（她笑了笑。）好。")));

        List<CompanionRoleDTO> rows = service.getRoles(USER);

        assertEquals(List.of(IRIS, NOOR), rows.stream().map(CompanionRoleDTO::getId).toList());
        CompanionRoleDTO irisRow = rows.get(0);
        CompanionRoleDTO noorRow = rows.get(1);
        assertEquals("（她笑了笑。）好。", irisRow.getLastMessage());
        assertEquals("role", irisRow.getLastMessageSender());
        assertEquals("那明天见", noorRow.getLastMessage());
        assertEquals("user", noorRow.getLastMessageSender());
        // The fields on either side of the new one are still in their own places.
        assertEquals(SPOKEN_AT, irisRow.getLastMessageAt());
        assertEquals("把杯子往前推了推", irisRow.getPresence());
        assertEquals("守着这间酒馆的人", irisRow.getBackground());
        assertEquals("（她抬起头。）你来了。", irisRow.getGreeting());
        // The name the client reads it by.
        assertEquals("user", new ObjectMapper().findAndRegisterModules().valueToTree(noorRow)
                .path("lastMessageSender").asText());

        // One read per table however long the list is; nothing per character.
        verify(messageRepository).findLatestPerRole(USER);
        verify(conversationRepository).findByUserId(USER);
        verify(statusRepository).findByUserId(USER);
        verifyNoMoreInteractions(messageRepository, conversationRepository, statusRepository);
    }

    @Test
    void aCharacterNobodyHasSpokenToHasNoLineAndNoSender() {
        CompanionRole iris = role(IRIS, "艾瑞斯");
        iris.setGreeting("（她抬起头。）你来了。");
        when(roleRepository.findByUserIdOrderByIdAsc(USER)).thenReturn(List.of(iris));
        when(statusRepository.findByUserId(USER)).thenReturn(List.of(status(IRIS, "靠在吧台边看雨")));
        when(statusRepository.findByUserIdAndRoleId(USER, IRIS)).thenReturn(Optional.of(status(IRIS, "靠在吧台边看雨")));
        when(messageRepository.findLatestPerRole(USER)).thenReturn(List.of());
        when(messageRepository.findLatestForRole(USER, IRIS)).thenReturn(Optional.empty());

        // In the list, and handed back on her own the way an invited guest is.
        for (CompanionRoleDTO row : List.of(service.getRoles(USER).get(0), service.toRoleDTO(iris))) {
            assertNull(row.getLastMessage());
            assertNull(row.getLastMessageSender());
            assertNull(row.getLastMessageAt());
            // Her opening line and what she is doing stay in their own fields; neither stands in for a message.
            assertEquals("（她抬起头。）你来了。", row.getGreeting());
            assertEquals("靠在吧台边看雨", row.getPresence());
        }
    }

    @Test
    void savingTheProfileHandsTheRowBackWithItsPreviewTimeAndPresence() {
        CompanionRole iris = role(IRIS, "艾瑞斯");
        when(roleRepository.findByIdAndUserId(IRIS, USER)).thenReturn(Optional.of(iris));
        when(conversationRepository.findByUserIdAndRoleId(USER, IRIS)).thenReturn(Optional.of(conversation(IRIS)));
        when(statusRepository.findByUserIdAndRoleId(USER, IRIS)).thenReturn(Optional.of(status(IRIS, "擦着杯子")));
        when(messageRepository.findLatestForRole(USER, IRIS))
                .thenReturn(Optional.of(message(IRIS, SenderType.user, "晚安")));
        CompanionRoleDTO edit = new CompanionRoleDTO();
        edit.setName("艾瑞丝");
        // Whatever preview the client sends along is not where the answer comes from.
        edit.setLastMessage("客户端手里的旧预览");
        edit.setLastMessageSender("role");

        CompanionRoleDTO saved = service.updateRole(USER, IRIS, edit);

        verify(roleRepository).save(iris);
        assertEquals("艾瑞丝", saved.getName());
        assertEquals("晚安", saved.getLastMessage());
        assertEquals("user", saved.getLastMessageSender());
        assertEquals(SPOKEN_AT, saved.getLastMessageAt());
        assertEquals("擦着杯子", saved.getPresence());
        // An edit reads the one line it needs and writes nothing to the conversation.
        verify(messageRepository).findLatestForRole(USER, IRIS);
        verifyNoMoreInteractions(messageRepository);
    }

    @Test
    void aSenderTheRowCannotNameIsLeftAsItIsRatherThanGuessed() {
        when(roleRepository.findByUserIdOrderByIdAsc(USER)).thenReturn(List.of(role(IRIS, "艾瑞斯"), role(NOOR, "诺尔")));
        when(messageRepository.findLatestPerRole(USER)).thenReturn(List.of(
                message(IRIS, null, "没有署名的一行"),
                message(NOOR, SenderType.system, "这一晚已经收起")));

        List<CompanionRoleDTO> rows = service.getRoles(USER);

        assertEquals("没有署名的一行", rows.get(0).getLastMessage());
        assertNull(rows.get(0).getLastMessageSender());
        assertEquals("这一晚已经收起", rows.get(1).getLastMessage());
        assertEquals("system", rows.get(1).getLastMessageSender());
    }

    private static CompanionRole role(long id, String name) {
        CompanionRole role = new CompanionRole();
        role.setId(id);
        role.setUserId(USER);
        role.setName(name);
        return role;
    }

    private static CompanionConversation conversation(long roleId) {
        CompanionConversation conversation = new CompanionConversation();
        conversation.setUserId(USER);
        conversation.setRoleId(roleId);
        conversation.setLastMessageAt(SPOKEN_AT);
        return conversation;
    }

    private static CompanionStatus status(long roleId, String summary) {
        CompanionStatus status = new CompanionStatus();
        status.setUserId(USER);
        status.setRoleId(roleId);
        status.setSummary(summary);
        return status;
    }

    private static CompanionMessage message(long roleId, SenderType sender, String content) {
        CompanionMessage message = new CompanionMessage();
        message.setUserId(USER);
        message.setRoleId(roleId);
        message.setSenderType(sender);
        message.setContent(content);
        return message;
    }
}
