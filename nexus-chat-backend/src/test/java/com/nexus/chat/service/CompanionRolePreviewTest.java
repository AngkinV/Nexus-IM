package com.nexus.chat.service;

import com.nexus.chat.dto.CompanionRoleDTO;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The seat list's preview line, with every repository mocked: the single-role
 * mapping must report the same last message the bulk list does, the bulk list
 * must stay at one query per table, and nothing a client sends can rewrite it.
 */
class CompanionRolePreviewTest {
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
                mock(CompanionModelResolver.class), assets);
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
        // A newest message with nothing in it is no preview, not an older line.
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
        verify(messages).findFirstByUserIdAndRoleIdOrderByIdDesc(USER, ROLE);
        verifyNoMoreInteractions(messages);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  \n "})
    void aSingleCharacterWithNoUsableLastLineHasNoPreview(String content) {
        when(messages.findFirstByUserIdAndRoleIdOrderByIdDesc(USER, ROLE))
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
        verify(messages).findFirstByUserIdAndRoleIdOrderByIdDesc(USER, ROLE);
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
        when(messages.findFirstByUserIdAndRoleIdOrderByIdDesc(USER, ROLE)).thenReturn(Optional.of(last));
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
        when(messages.findFirstByUserIdAndRoleIdOrderByIdDesc(USER, ROLE))
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
