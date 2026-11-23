package com.nexus.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.CompanionRoleDTO;
import com.nexus.chat.model.*;
import com.nexus.chat.model.CompanionMessage.SenderType;
import com.nexus.chat.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * What a seat-list row says about its conversation, with the database mocked.
 * Two groups written by two threads against the same service; both hold.
 */
class CompanionRolePreviewTest {

    /** The row's line and its sender, read off one message. */
    @Nested
    @ExtendWith(MockitoExtension.class)
    class LineAndSender {

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
        @Mock CompanionModelAssetCatalog modelAssetCatalog;
        @Mock UserAssetService userAssetService;

        private CompanionService service;

        @BeforeEach
        void setUp() {
            service = new CompanionService(roleRepository, growthRepository, memoryRepository,
                    conversationRepository, messageRepository, modelBindingRepository, credentialRepository,
                    statusRepository, new CompanionFallbackService(), modelService, cryptoService,
                    sessionRepository, modelResolver, modelAssetCatalog, userAssetService);
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

    /**
     * The single-role mapping must report the same last message the bulk list
     * does, the bulk list must stay at one query per table, and nothing a client
     * sends can rewrite it.
     */
    @Nested
    class RecencyAndUpdates {
        private static final Long USER = 7L;
        private static final Long ROLE = 41L;
        private static final LocalDateTime LAST_AT = LocalDateTime.of(2026, 11, 20, 21, 30);

        private final CompanionRoleRepository roles = mock(CompanionRoleRepository.class);
        private final CompanionConversationRepository conversations = mock(CompanionConversationRepository.class);
        private final CompanionMessageRepository messages = mock(CompanionMessageRepository.class);
        private final CompanionStatusRepository statuses = mock(CompanionStatusRepository.class);
        private final CompanionModelAssetCatalog assets = mock(CompanionModelAssetCatalog.class);
        private CompanionService service;
        private CompanionRole role;

        @BeforeEach
        void setUp() {
            service = new CompanionService(roles, mock(CompanionGrowthRepository.class),
                    mock(CompanionMemoryRepository.class), conversations, messages,
                    mock(CompanionModelBindingRepository.class), mock(ModelCredentialRepository.class), statuses,
                    new CompanionFallbackService(), mock(CompanionModelService.class),
                    mock(CompanionCryptoService.class), mock(TavernSessionRepository.class),
                    mock(CompanionModelResolver.class), assets, mock(UserAssetService.class));
            when(assets.requireAsset(anyString())).thenAnswer(invocation -> {
                String url = invocation.getArgument(0);
                return new CompanionModelAssetCatalog.ModelAsset(url.substring(url.lastIndexOf('/') + 1), url, 24, "vrm", true);
            });
            role = role(ROLE);
            role.setKind(CompanionRole.Kind.guest);
            role.setModelUrl("/models/a.vrm");
            role.setModelType("vrm");
            when(roles.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.of(role));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 4, 40})
        void theSeatListCostsOneQueryPerTableHoweverManyCharactersThereAre(int extraRoles) {
            List<CompanionRole> owned = new ArrayList<>(List.of(role(1L), role(2L), role(3L), role(4L)));
            for (long id = 100; id < 100 + extraRoles; id++) owned.add(role(id));
            when(roles.findByUserIdOrderByIdAsc(USER)).thenReturn(owned);
            when(conversations.findByUserId(USER)).thenReturn(List.of(conversation(1L, LAST_AT), conversation(2L, null)));
            when(statuses.findByUserId(USER)).thenReturn(List.of(status(1L, "擦着杯子")));
            when(messages.findLatestPerRole(USER)).thenReturn(List.of(
                    message(1L, "晚安。"), message(2L, "   "), message(4L, null), message(100L, "路过。")));

            List<CompanionRoleDTO> listed = service.getRoles(USER);

            assertEquals(4 + extraRoles, listed.size());
            assertEquals("晚安。", listed.get(0).getLastMessage());
            assertEquals(LAST_AT, listed.get(0).getLastMessageAt());
            assertEquals("擦着杯子", listed.get(0).getPresence());
            // A message with nothing in it, should the repository hand one back, is no preview.
            assertNull(listed.get(1).getLastMessage());
            assertNull(listed.get(2).getLastMessage());
            assertNull(listed.get(3).getLastMessage());
            assertEquals("路过。", listed.get(4).getLastMessage());

            verify(roles).findByUserIdOrderByIdAsc(USER);
            verify(conversations).findByUserId(USER);
            verify(statuses).findByUserId(USER);
            verify(messages).findLatestPerRole(USER);
            // Nothing per character: no single-row lookup of any kind.
            verifyNoMoreInteractions(roles, conversations, statuses, messages);
        }

        @Test
        void aSingleCharacterReportsItsRealLastMessageRecencyAndPresence() {
            hasHistory("最后一句。");

            CompanionRoleDTO dto = service.toRoleDTO(role);

            assertEquals("最后一句。", dto.getLastMessage());
            assertEquals(LAST_AT, dto.getLastMessageAt());
            assertEquals("望着雨幕", dto.getPresence());
            verify(messages).findLatestForRole(USER, ROLE);
            verifyNoMoreInteractions(messages);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  \n "})
        void aSingleCharacterWithNoUsableLastLineHasNoPreview(String content) {
            when(messages.findLatestForRole(USER, ROLE))
                    .thenReturn(Optional.of(message(ROLE, content)));

            assertNull(service.toRoleDTO(role).getLastMessage());
        }

        @Test
        void aCharacterNobodyHasSpokenToHasNoPreview() {
            CompanionRoleDTO dto = service.toRoleDTO(role);

            assertNull(dto.getLastMessage());
            assertNull(dto.getLastMessageAt());
            assertNull(dto.getPresence());
        }

        @Test
        void changingTheModelInSettingsKeepsTheLastMessageInTheResponse() {
            hasHistory("明天见。");
            CompanionRoleDTO update = new CompanionRoleDTO();
            update.setModelUrl("/models/b.vrm");
            update.setModelType("vrm");

            CompanionRoleDTO dto = service.updateRole(USER, ROLE, update);

            assertEquals("/models/b.vrm", dto.getModelUrl());
            assertEquals("明天见。", dto.getLastMessage());
            assertEquals(LAST_AT, dto.getLastMessageAt());
            assertEquals("望着雨幕", dto.getPresence());
            assertEquals("/models/b.vrm", role.getModelUrl());
            verify(roles).save(role);
        }

        @Test
        void anUpdateCannotRewriteThePreviewRecencyPresenceKindOrVisibility() {
            hasHistory("真实的最后一句。");
            CompanionRoleDTO update = new CompanionRoleDTO();
            update.setId(999L);
            update.setName("新名字");
            update.setLastMessage("伪造的预览");
            update.setLastMessageAt(LocalDateTime.of(1999, 1, 1, 0, 0));
            update.setPresence("伪造的状态");
            update.setKind("regular");
            update.setConversationHidden(true);

            CompanionRoleDTO dto = service.updateRole(USER, ROLE, update);

            // The writable field went through; every derived one is the server's.
            assertEquals("新名字", dto.getName());
            assertEquals(ROLE, dto.getId());
            assertEquals("真实的最后一句。", dto.getLastMessage());
            assertEquals(LAST_AT, dto.getLastMessageAt());
            assertEquals("望着雨幕", dto.getPresence());
            assertEquals("guest", dto.getKind());
            assertFalse(dto.isConversationHidden());
            assertEquals(ROLE, role.getId());
            assertEquals(CompanionRole.Kind.guest, role.getKind());
            assertFalse(role.isConversationHidden());
            // History, recency and status were read, never written.
            verify(messages).findLatestForRole(USER, ROLE);
            verify(conversations).findByUserIdAndRoleId(USER, ROLE);
            verify(statuses).findByUserIdAndRoleId(USER, ROLE);
            verifyNoMoreInteractions(messages, conversations, statuses);
        }

        @Test
        void anotherAccountsCharacterCannotBeUpdated() {
            CompanionRoleDTO update = new CompanionRoleDTO();
            update.setModelUrl("/models/b.vrm");

            assertThrows(RuntimeException.class, () -> service.updateRole(USER + 1, ROLE, update));

            assertEquals("/models/a.vrm", role.getModelUrl());
            verify(roles, never()).save(any());
            verifyNoInteractions(messages, conversations, statuses);
        }

        @Test
        void previewTimeComesFromTheMessageRatherThanTheEndOfModelProcessing() {
            hasHistory("hello");
            CompanionMessage last = message(ROLE, "hello");
            LocalDateTime filedAt = LAST_AT.minusMinutes(3);
            last.setCreatedAt(filedAt);
            when(messages.findLatestForRole(USER, ROLE)).thenReturn(Optional.of(last));
            assertEquals(filedAt, service.toRoleDTO(role).getLastMessageAt());
            when(roles.findByUserIdOrderByIdAsc(USER)).thenReturn(List.of(role));
            when(conversations.findByUserId(USER)).thenReturn(List.of(conversation(ROLE, LAST_AT)));
            when(messages.findLatestPerRole(USER)).thenReturn(List.of(last));
            assertEquals(filedAt, service.getRoles(USER).get(0).getLastMessageAt());
        }

        @Test
        void aConversationWithNoMessageHasNoPhantomCreationTimestamp() {
            when(conversations.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(conversation(ROLE, LAST_AT)));
            assertNull(service.toRoleDTO(role).getLastMessageAt());
            when(roles.findByUserIdOrderByIdAsc(USER)).thenReturn(List.of(role));
            when(conversations.findByUserId(USER)).thenReturn(List.of(conversation(ROLE, LAST_AT)));
            assertNull(service.getRoles(USER).get(0).getLastMessageAt());
        }

        private void hasHistory(String lastLine) {
            when(messages.findLatestForRole(USER, ROLE))
                    .thenReturn(Optional.of(message(ROLE, lastLine)));
            when(conversations.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(conversation(ROLE, LAST_AT)));
            when(statuses.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(status(ROLE, "望着雨幕")));
        }

        private CompanionRole role(Long id) {
            var owned = new CompanionRole();
            owned.setId(id);
            owned.setUserId(USER);
            owned.setName("角色" + id);
            return owned;
        }

        private CompanionConversation conversation(Long roleId, LocalDateTime lastMessageAt) {
            var conversation = new CompanionConversation();
            conversation.setUserId(USER);
            conversation.setRoleId(roleId);
            conversation.setLastMessageAt(lastMessageAt);
            return conversation;
        }

        private CompanionStatus status(Long roleId, String summary) {
            var status = new CompanionStatus();
            status.setUserId(USER);
            status.setRoleId(roleId);
            status.setSummary(summary);
            return status;
        }

        private CompanionMessage message(Long roleId, String content) {
            var message = new CompanionMessage();
            message.setUserId(USER);
            message.setRoleId(roleId);
            message.setContent(content);
            return message;
        }
    }
}
