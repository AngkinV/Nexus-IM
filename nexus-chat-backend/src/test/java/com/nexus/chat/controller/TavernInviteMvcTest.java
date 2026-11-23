package com.nexus.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.CompanionRoleDTO;
import com.nexus.chat.exception.GlobalExceptionHandler;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The HTTP contract of inviting a guest and of the two model lists, through the
 * real controllers, services and catalogue in a standalone MockMvc.
 *
 * No application context: repositories, the model and the transaction manager
 * are mocks, and the models directory is a JUnit temp directory. Security
 * filters are not part of a standalone setup, so only the controllers' own
 * {@code CurrentUser} check is exercised here.
 */
class TavernInviteMvcTest {
    private static final Long USER = 7L;
    private static final Long ROLE = 41L;
    private static final String INVITE = "/api/tavern/roles/invite";
    private static final String INVITE_MODELS = "/api/tavern/roles/models";
    private static final String SETTINGS_MODELS = "/api/companion/assets/models";

    @TempDir
    Path modelsDir;

    @TempDir
    Path outsideDir;

    private final TavernSessionRepository sessions = mock(TavernSessionRepository.class);
    private final CompanionRoleRepository roles = mock(CompanionRoleRepository.class);
    private final CompanionMessageRepository messages = mock(CompanionMessageRepository.class);
    private final CompanionConversationRepository conversations = mock(CompanionConversationRepository.class);
    private final CompanionStatusRepository statuses = mock(CompanionStatusRepository.class);
    private final CompanionModelResolver resolver = mock(CompanionModelResolver.class);
    private final CompanionModelService model = mock(CompanionModelService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final CompanionConversationGuard guard = new CompanionConversationGuard();
    private final CompanionModelAssetCatalog catalog = new CompanionModelAssetCatalog();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private TavernSessionService sessionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER, null, List.of()));
        ReflectionTestUtils.setField(catalog, "modelsDir", modelsDir.toString());
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        // No usable channel: every invite here uses the built-in persona, so no model request exists to make.
        when(resolver.resolveForUser(USER)).thenReturn(new CompanionModelResolver.Resolved(null, null, true));
        when(roles.save(any(CompanionRole.class))).thenAnswer(invocation -> {
            CompanionRole saved = invocation.getArgument(0);
            saved.setId(ROLE);
            return saved;
        });
        CompanionService companionService = new CompanionService(roles, mock(CompanionGrowthRepository.class),
                mock(CompanionMemoryRepository.class), conversations, messages,
                mock(CompanionModelBindingRepository.class), mock(ModelCredentialRepository.class), statuses,
                new CompanionFallbackService(), model, mock(CompanionCryptoService.class), sessions, resolver, catalog,
                mock(UserAssetService.class));
        sessionService = new TavernSessionService(sessions, roles, messages, mock(CompanionMemoryRepository.class),
                resolver, model, new CompanionFallbackService(), conversations, statuses, transactionManager,
                guard, catalog);
        mvc = MockMvcBuilders.standaloneSetup(
                        new TavernSessionController(sessionService, companionService, catalog),
                        new CompanionAssetController(new ObjectMapper(), catalog, roles, transactionManager),
                        new CompanionController(companionService, guard))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invitingAndListingRequireTheAuthenticatedUser() throws Exception {
        vrm("a.vrm");
        SecurityContextHolder.clearContext();

        mvc.perform(invite(Map.of("modelUrl", "/models/a.vrm")).param("userId", USER.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(INVITE_MODELS).param("userId", USER.toString()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(resolver, model, roles, transactionManager);
    }

    @Test
    void aClaimedUserIdThatIsNotTheCallerIsForbidden() throws Exception {
        mvc.perform(invite(Map.of()).param("userId", "8")).andExpect(status().isForbidden());

        verifyNoInteractions(resolver, model, roles, transactionManager);
    }

    @Test
    void theInviteListIsTheStageReadySubsetOfTheSettingsList() throws Exception {
        vrm("a.vrm");
        raw("b.glb");
        raw("c.gltf");
        raw("d.fbx");
        raw("broken.vrm");
        raw("notes.txt");
        Path linked = outsideDir.resolve("real.vrm");
        Files.write(linked, glb());
        Files.createSymbolicLink(modelsDir.resolve("link.vrm"), linked);

        mvc.perform(get(INVITE_MODELS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fileName").value("a.vrm"))
                .andExpect(jsonPath("$[0].fileUrl").value("/models/a.vrm"))
                .andExpect(jsonPath("$[0].size").value(24))
                .andExpect(jsonPath("$[0].type").value("vrm"))
                .andExpect(jsonPath("$[0].stageReady").doesNotExist());

        mvc.perform(get(SETTINGS_MODELS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].fileUrl").value(contains(
                        "/models/a.vrm", "/models/b.glb", "/models/broken.vrm", "/models/c.gltf", "/models/d.fbx")))
                .andExpect(jsonPath("$[*].type").value(contains("vrm", "gltf", "vrm", "gltf", "fbx")))
                .andExpect(jsonPath("$[0].stageReady").doesNotExist());
    }

    @Test
    void anEmptyDirectoryIsAnEmptyListOnBothEndpoints() throws Exception {
        mvc.perform(get(INVITE_MODELS)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(SETTINGS_MODELS)).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {INVITE_MODELS, SETTINGS_MODELS})
    void aDirectoryThatCannotBeReadIsA500WithACodeNotAnEmptyList(String url) throws Exception {
        modelsDirectoryIsUnreadable();

        mvc.perform(get(url))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("model_catalog_unavailable"));
    }

    @ParameterizedTest
    @CsvSource({
            "/models/missing.vrm, model_not_found",
            "missing.vrm, model_not_found",
            "/models/link.vrm, model_not_found",
            "/models/A.VRM, model_not_found",
            "/models/b.glb, model_type_unsupported",
            "/models/d.fbx, model_type_unsupported",
            "/models/broken.vrm, model_file_invalid",
            "../outside.vrm, model_url_invalid",
            "/models/../outside.vrm, model_url_invalid",
            "/models/sub/a.vrm, model_url_invalid",
            "/models/%2e%2e%2foutside.vrm, model_url_invalid",
            "/models/sub%2Fa.vrm, model_url_invalid",
            "https://evil.example/models/a.vrm, model_url_invalid",
            "//evil.example/a.vrm, model_url_invalid",
            "file:///etc/passwd, model_url_invalid",
            "\\models\\a.vrm, model_url_invalid",
            "/models/..\\outside.vrm, model_url_invalid",
            "/models/notes.txt, model_url_invalid"
    })
    void aChosenModelThatCannotBeWornIsA400NamingWhy(String modelUrl, String code) throws Exception {
        vrm("a.vrm");
        raw("b.glb");
        raw("d.fbx");
        raw("broken.vrm");
        raw("notes.txt");
        Files.write(modelsDir.getParent().resolve("outside.vrm"), glb());
        Path linked = outsideDir.resolve("real.vrm");
        Files.write(linked, glb());
        Files.createSymbolicLink(modelsDir.resolve("link.vrm"), linked);

        mvc.perform(invite(Map.of("sceneKey", "rain-fireplace", "modelUrl", modelUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(code));

        // Refused before anything was spent or stored, and no other model was put in its place.
        verifyNoInteractions(resolver, model, roles, statuses, transactionManager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"modelUrl\":123}", "{\"modelUrl\":{\"fileUrl\":\"/models/a.vrm\"}}",
            "{\"modelUrl\":[\"/models/a.vrm\"]}"})
    void aModelUrlThatIsNotAStringIsRefusedNotGuessedAt(String body) throws Exception {
        vrm("a.vrm");

        mvc.perform(post(INVITE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("model_url_invalid"));

        verifyNoInteractions(resolver, model, roles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/models/a.vrm", "a.vrm", "  /models/a.vrm  "})
    void aChosenModelIsStoredAndReturnedInCanonicalForm(String spelling) throws Exception {
        vrm("a.vrm");
        vrm("b.vrm");

        CompanionRoleDTO invited = read(mvc.perform(invite(Map.of("modelUrl", spelling)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertEquals("/models/a.vrm", invited.getModelUrl());
        assertEquals("vrm", invited.getModelType());
        assertEquals("guest", invited.getKind());
        assertEquals(ROLE, invited.getId());
        assertNull(invited.getLastMessage());
        assertNull(invited.getLastMessageAt());
        verifyNoInteractions(model);
    }

    @Test
    void withNoModelUploadedAnInviteStillSucceedsAsTextOnly() throws Exception {
        // No body at all, and an empty models directory.
        CompanionRoleDTO invited = read(mvc.perform(post(INVITE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertNull(invited.getModelUrl());
        assertNull(invited.getModelType());
        assertEquals("guest", invited.getKind());
    }

    @Test
    void anAutomaticInviteNeverPicksAModelTheStageCannotShow() throws Exception {
        raw("b.glb");
        raw("d.fbx");
        raw("broken.vrm");

        CompanionRoleDTO invited = read(mvc.perform(invite(Map.of("sceneKey", "rain-fireplace")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertNull(invited.getModelUrl());
        assertNull(invited.getModelType());
    }

    @Test
    void anAutomaticInviteSkipsModelsWornByVisibleCharactersHoweverTheyWereStored() throws Exception {
        vrm("a.vrm");
        vrm("b.vrm");
        vrm("c.vrm");
        when(roles.findByUserIdOrderByIdAsc(USER)).thenReturn(List.of(
                wearing("a.vrm", false),            // bare name
                wearing("/models/b.vrm", false),
                wearing("/models/c.vrm", true)));   // hidden: does not hold c.vrm

        for (int i = 0; i < 8; i++) {
            CompanionRoleDTO invited = read(mvc.perform(invite(Map.of()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertEquals("/models/c.vrm", invited.getModelUrl());
        }
    }

    @Test
    void anAutomaticInviteWithAnUnreadableDirectoryIsA500BeforeAnythingIsSpent() throws Exception {
        modelsDirectoryIsUnreadable();

        mvc.perform(invite(Map.of()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("model_catalog_unavailable"));

        verifyNoInteractions(resolver, model, statuses, transactionManager);
        verify(roles, never()).save(any());
    }

    @Test
    void aSecondInviteWhileOneIsRunningIsA409AndTheNextOneAfterItSucceeds() throws Exception {
        var resolved = usableModel();
        when(resolver.resolveForUser(USER)).thenReturn(resolved);
        var modelEntered = new CountDownLatch(1);
        var finishModel = new CountDownLatch(1);
        when(model.generateRole(resolved.binding(), resolved.credential(), "rain-fireplace")).thenAnswer(inv -> {
            modelEntered.countDown();
            assertTrue(finishModel.await(5, TimeUnit.SECONDS), "the test must release the mocked model");
            return new CompanionModelService.GeneratedRole("旅人", "沉默", "简短", "望着雨幕", "从山间而来", "晚上好。");
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            // The first invite is this account's other tab: the service call, mid model request.
            var first = executor.submit(() -> sessionService.inviteRole(USER, "rain-fireplace", null));
            assertTrue(modelEntered.await(5, TimeUnit.SECONDS), "the first invite must reach the mocked model");

            mvc.perform(invite(Map.of("sceneKey", "rain-fireplace")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("invite_in_progress"));
            verify(model, times(1)).generateRole(any(), any(), any());
            verify(roles, never()).save(any());

            finishModel.countDown();
            assertEquals("旅人", first.get(5, TimeUnit.SECONDS).getName());
            mvc.perform(invite(Map.of("sceneKey", "rain-fireplace"))).andExpect(status().isOk());
        } finally {
            finishModel.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void updatingARoleReturnsTheRealPreviewAndIgnoresTheOneTheClientSent() throws Exception {
        vrm("b.vrm");
        LocalDateTime lastAt = LocalDateTime.of(2026, 11, 20, 21, 30);
        CompanionRole role = wearing("/models/a.vrm", false);
        role.setId(ROLE);
        role.setKind(CompanionRole.Kind.guest);
        when(roles.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.of(role));
        CompanionMessage last = new CompanionMessage();
        last.setContent("真实的最后一句。");
        when(messages.findLatestForRole(USER, ROLE)).thenReturn(Optional.of(last));
        CompanionConversation conversation = new CompanionConversation();
        conversation.setLastMessageAt(lastAt);
        when(conversations.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(conversation));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelUrl", "/models/b.vrm");
        body.put("modelType", "vrm");
        body.put("id", 999);
        body.put("lastMessage", "伪造的预览");
        body.put("lastMessageAt", "1999-01-01T00:00:00");
        body.put("presence", "伪造的状态");
        body.put("kind", "regular");
        body.put("conversationHidden", true);
        CompanionRoleDTO updated = read(mvc.perform(put("/api/companion/roles/" + ROLE)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertEquals("/models/b.vrm", updated.getModelUrl());
        assertEquals(ROLE, updated.getId());
        assertEquals("真实的最后一句。", updated.getLastMessage());
        assertEquals(lastAt, updated.getLastMessageAt());
        assertNull(updated.getPresence());
        assertEquals("guest", updated.getKind());
        assertFalse(updated.isConversationHidden());
        assertEquals(CompanionRole.Kind.guest, role.getKind());
        assertFalse(role.isConversationHidden());
        verify(messages, never()).save(any());
        verify(conversations, never()).save(any());
        verify(statuses, never()).save(any());
    }

    private MockHttpServletRequestBuilder invite(Map<String, Object> body) throws Exception {
        return post(INVITE).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    /**
     * Read from the bytes: the mock response decodes a body without a charset as
     * ISO-8859-1. Dates come back in whatever form the converter writes, and
     * reading them as the DTO is format-neutral.
     */
    private CompanionRoleDTO read(byte[] responseBody) throws Exception {
        return json.readValue(responseBody, CompanionRoleDTO.class);
    }

    /** A minimal binary glTF container: header, then one JSON chunk holding "{}". */
    private static byte[] glb() {
        byte[] chunk = "{}  ".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(20 + chunk.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67).putInt(2).putInt(20 + chunk.length)
                .putInt(chunk.length).putInt(0x4E4F534A).put(chunk)
                .array();
    }

    private void vrm(String name) throws Exception {
        Files.write(modelsDir.resolve(name), glb());
    }

    private void raw(String name) throws Exception {
        Files.write(modelsDir.resolve(name), new byte[] {1, 2, 3});
    }

    /** The configured path exists but is a file, so listing it fails. */
    private void modelsDirectoryIsUnreadable() throws Exception {
        Path notADirectory = outsideDir.resolve("models-is-a-file");
        Files.write(notADirectory, new byte[] {1});
        ReflectionTestUtils.setField(catalog, "modelsDir", notADirectory.toString());
    }

    private CompanionRole wearing(String modelUrl, boolean conversationHidden) {
        CompanionRole worn = new CompanionRole();
        worn.setUserId(USER);
        worn.setModelUrl(modelUrl);
        worn.setConversationHidden(conversationHidden);
        return worn;
    }

    private CompanionModelResolver.Resolved usableModel() {
        CompanionModelBinding binding = new CompanionModelBinding();
        binding.setEndpoint("https://example.invalid/v1");
        binding.setModelName("test-model");
        ModelCredential credential = new ModelCredential();
        credential.setApiKeyEncrypted("test-only");
        return new CompanionModelResolver.Resolved(binding, credential, true);
    }
}
