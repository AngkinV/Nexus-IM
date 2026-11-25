package com.nexus.chat.tools;

import com.nexus.chat.config.FunnelSecretGuard;
import com.nexus.chat.service.CompanionCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic ciphertext, an in-memory database and a fake Keychain only. */
class FunnelSecretPrepTest {
    private static final String OLD = "dev-synthetic-companion-secret";
    private static final String STRONG = "fixture-only-not-for-use-outside-tests-";

    private static final class FakeKeychain implements FunnelSecretPrep.Keychain {
        final Map<String, String> items = new LinkedHashMap<>();
        final List<String> finds = new ArrayList<>();
        boolean dropWrites;

        @Override public Optional<String> find(String account) {
            finds.add(account);
            return Optional.ofNullable(items.get(account));
        }

        // Like `security add-generic-password` without -U: an existing item is never replaced.
        @Override public void add(String account, String value) {
            if (!dropWrites) items.putIfAbsent(account, value);
        }
    }

    private String url;
    private final FakeKeychain keychain = new FakeKeychain();
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    @BeforeEach
    void database() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        CompanionCryptoService old = new CompanionCryptoService(OLD);
        try (Connection db = DriverManager.getConnection(url); Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE model_credentials (id BIGINT PRIMARY KEY, api_key_encrypted CLOB)");
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO model_credentials VALUES (?, ?)")) {
                for (Object[] row : new Object[][]{{1L, old.encrypt("synthetic-key-one")}, {2L, old.encrypt("合成密钥二")},
                        {3L, null}, {4L, ""}, {5L, old.encrypt("synthetic-key-five")}}) {
                    insert.setLong(1, (Long) row[0]);
                    insert.setString(2, (String) row[1]);
                    insert.executeUpdate();
                }
            }
        }
    }

    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("server.port", "8080")
                .withProperty("agent.python.base-url", "http://localhost:8100")
                .withProperty("jwt.secret", STRONG + "jwt")
                .withProperty("agent.internal.token", "dev-synthetic-token")
                .withProperty("agent.internal.signing-secret", "")
                .withProperty("companion.api-key.secret", OLD);
    }

    private boolean run(boolean prepare, Connection db, boolean backendsUp, String typed) throws Exception {
        return FunnelSecretPrep.run(prepare, environment(), keychain, db, (host, port) -> backendsUp, () -> typed,
                new PrintStream(printed, true, StandardCharsets.UTF_8));
    }

    private boolean run(boolean prepare, boolean backendsUp, String typed) throws Exception {
        try (Connection db = DriverManager.getConnection(url)) {
            return run(prepare, db, backendsUp, typed);
        }
    }

    private Map<Long, String> rows() throws Exception {
        Map<Long, String> rows = new LinkedHashMap<>();
        try (Connection db = DriverManager.getConnection(url); Statement statement = db.createStatement();
             ResultSet result = statement.executeQuery("SELECT id, api_key_encrypted FROM model_credentials ORDER BY id")) {
            while (result.next()) rows.put(result.getLong(1), result.getString(2));
        }
        return rows;
    }

    private void assertNoSecretPrinted() {
        String text = printed.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains(OLD));
        assertFalse(text.contains("synthetic-key"));
        for (Map.Entry<String, String> item : keychain.items.entrySet()) {
            assertFalse(text.contains(item.getValue()));
            // The archive account ends in a fingerprint of the old key.
            if (item.getKey().contains(".old.")) assertFalse(text.contains(item.getKey().substring(item.getKey().lastIndexOf('.') + 1)));
        }
    }

    private String reported(Exception failure) {
        FunnelSecretPrep.reportFailure(failure, new PrintStream(printed, true, StandardCharsets.UTF_8));
        return printed.toString(StandardCharsets.UTF_8);
    }

    @Test
    void checkReportsNotReadyAndChangesNothing() throws Exception {
        Map<Long, String> before = rows();
        assertFalse(run(false, true, null));
        assertEquals(before, rows());
        assertTrue(keychain.items.isEmpty());
        String text = printed.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("待迁移 3"));
        assertTrue(text.contains("agent.internal.token, agent.internal.signing-secret, companion.api-key.secret"));
        assertFalse(text.contains("jwt.secret"));
        assertNoSecretPrinted();
    }

    @Test
    void prepareRefusesWhileABackendListensOrWithoutTheTypedConfirmation() throws Exception {
        Map<Long, String> before = rows();
        assertFalse(run(true, true, FunnelSecretPrep.CONFIRMATION));
        assertFalse(run(true, false, "yes"));
        assertFalse(run(true, false, null));
        assertEquals(before, rows());
        assertTrue(keychain.items.isEmpty());
    }

    @Test
    void prepareStoresKeysThenReEncryptsEveryRowAndIsRepeatable() throws Exception {
        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));

        assertFalse(keychain.items.containsKey("jwt.secret"));
        MockEnvironment launched = environment();
        keychain.items.forEach((key, value) -> { if (!key.contains(".old.")) launched.setProperty(key, value); });
        assertTrue(new FunnelSecretGuard(launched).validated());

        CompanionCryptoService current = new CompanionCryptoService(keychain.items.get("companion.api-key.secret"));
        Map<Long, String> migrated = rows();
        assertEquals("synthetic-key-one", current.decrypt(migrated.get(1L)));
        assertEquals("合成密钥二", current.decrypt(migrated.get(2L)));
        assertNull(migrated.get(3L));
        assertEquals("", migrated.get(4L));
        assertEquals("synthetic-key-five", current.decrypt(migrated.get(5L)));

        String archive = keychain.items.keySet().stream().filter(key -> key.startsWith("companion.api-key.secret.old.")).findFirst().orElseThrow();
        assertEquals(OLD, new String(Base64.getUrlDecoder().decode(keychain.items.get(archive)), StandardCharsets.UTF_8));
        assertNoSecretPrinted();

        Map<String, String> keys = new LinkedHashMap<>(keychain.items);
        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));
        assertTrue(run(false, false, null));
        assertEquals(keys, keychain.items);
        assertEquals(migrated, rows());
    }

    @Test
    void aRowNoKnownKeyCanDecryptAbortsBeforeAnyWrite() throws Exception {
        try (Connection db = DriverManager.getConnection(url); Statement statement = db.createStatement()) {
            statement.execute("INSERT INTO model_credentials VALUES (9, '"
                    + new CompanionCryptoService("another-synthetic-secret").encrypt("lost") + "')");
        }
        Map<Long, String> before = rows();
        assertFalse(run(true, false, FunnelSecretPrep.CONFIRMATION));
        assertEquals(before, rows());
        assertTrue(keychain.items.isEmpty());
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("无法解密 1"));
    }

    @Test
    void aKeychainThatDoesNotKeepTheNewKeyStopsBeforeTheDatabaseIsTouched() throws Exception {
        keychain.dropWrites = true;
        Map<Long, String> before = rows();
        String text = reported(assertThrows(IllegalStateException.class, () -> run(true, false, FunnelSecretPrep.CONFIRMATION)));
        assertEquals(before, rows());
        // The first key the tool has to generate is the one whose write was lost.
        assertTrue(text.contains("所在步骤: 写入并回读 Keychain 项 " + FunnelSecretGuard.REQUIRED.get(1)));
    }

    @Test
    void aFailureMidMigrationRollsBackAndTheSameKeyFinishesTheNextRun() throws Exception {
        Map<Long, String> before = rows();
        AtomicInteger updates = new AtomicInteger();
        try (Connection real = DriverManager.getConnection(url)) {
            Connection failing = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = invoke(real, method, args);
                        if (!method.getName().equals("prepareStatement")) return result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (p, m, a) -> {
                                    if (m.getName().equals("executeUpdate") && updates.incrementAndGet() == 2) {
                                        throw new SQLException("synthetic failure");
                                    }
                                    return invoke(result, m, a);
                                });
                    });
            String text = reported(assertThrows(SQLException.class, () -> run(true, failing, false, FunnelSecretPrep.CONFIRMATION)));
            assertTrue(text.contains("所在步骤: 更新并回读 model_credentials.api_key_encrypted"));
            assertFalse(text.contains("synthetic failure"));
            assertNoSecretPrinted();
        }
        assertEquals(before, rows());
        Map<String, String> keys = new LinkedHashMap<>(keychain.items);
        assertTrue(keys.containsKey("companion.api-key.secret"));
        assertTrue(keys.keySet().stream().anyMatch(key -> key.startsWith("companion.api-key.secret.old.")));

        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));
        assertEquals(keys, keychain.items);
        assertEquals("synthetic-key-five",
                new CompanionCryptoService(keys.get("companion.api-key.secret")).decrypt(rows().get(5L)));
    }

    @Test
    void springConfigurationResolvesWithoutAnApplicationContext() {
        var environment = FunnelSecretPrep.resolveEnvironment();
        assertEquals("127.0.0.1", environment.getProperty("server.address"));
        assertTrue(environment.getRequiredProperty("spring.datasource.url").startsWith("jdbc:"));
        assertNotNull(environment.getRequiredProperty("agent.python.base-url"));
    }

    @Test
    void invalidStoredTargetRefusesBeforeAnyKeychainOrDatabaseWrite() throws Exception {
        keychain.items.put("companion.api-key.secret", "synthetic-short-target");
        Map<Long, String> before = rows();
        Map<String, String> existing = new LinkedHashMap<>(keychain.items);
        assertFalse(run(true, false, FunnelSecretPrep.CONFIRMATION));
        assertEquals(before, rows());
        assertEquals(existing, keychain.items);
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("已有 Keychain 项"));
        assertNoSecretPrinted();
    }

    @Test
    void jwtIsNeverGeneratedQueriedOrOverridden() throws Exception {
        Map<Long, String> before = rows();
        try (Connection db = DriverManager.getConnection(url)) {
            assertFalse(FunnelSecretPrep.run(true, environment().withProperty("jwt.secret", "synthetic-short-jwt"),
                    keychain, db, (host, port) -> false, () -> FunnelSecretPrep.CONFIRMATION,
                    new PrintStream(printed, true, StandardCharsets.UTF_8)));
        }
        assertEquals(before, rows());
        assertTrue(keychain.items.isEmpty());
        assertTrue(keychain.finds.isEmpty());
        keychain.items.put("jwt.secret", "synthetic-unrelated-keychain-jwt");
        run(false, false, null);
        assertFalse(keychain.finds.contains("jwt.secret"));
        assertNoSecretPrinted();
    }

    @Test
    void blankLegacyConfigurationRefusesMigrationBeforeGeneratingKeys() throws Exception {
        try (Connection db = DriverManager.getConnection(url); Statement statement = db.createStatement()) {
            statement.execute("DELETE FROM model_credentials");
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO model_credentials VALUES (1, ?)")) {
                insert.setString(1, new CompanionCryptoService("").encrypt("synthetic-fallback-data"));
                insert.executeUpdate();
            }
        }
        Map<Long, String> before = rows();
        for (boolean prepare : new boolean[]{false, true}) {
            try (Connection db = DriverManager.getConnection(url)) {
                assertFalse(FunnelSecretPrep.run(prepare, environment().withProperty("companion.api-key.secret", ""),
                        keychain, db, (host, port) -> false, () -> FunnelSecretPrep.CONFIRMATION,
                        new PrintStream(printed, true, StandardCharsets.UTF_8)));
            }
        }
        assertEquals(before, rows());
        assertTrue(keychain.items.isEmpty());
        // check must not send the user to a prepare that is going to refuse.
        assertFalse(printed.toString(StandardCharsets.UTF_8).contains("需由用户执行 prepare"));
    }

    @Test
    void failureOutputNeverEchoesPrivateExceptionMessages() {
        FunnelSecretPrep.reportFailure(new SQLException("synthetic-private-url-and-password"),
                new PrintStream(printed, true, StandardCharsets.UTF_8));
        String text = printed.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("SQLException"));
        assertFalse(text.contains("synthetic-private-url-and-password"));
    }

    private boolean restore(Connection db, boolean backendsUp, String typed) throws Exception {
        return FunnelSecretPrep.restore(environment(), keychain, db, (host, port) -> backendsUp, () -> typed,
                new PrintStream(printed, true, StandardCharsets.UTF_8));
    }

    private boolean restore(boolean backendsUp, String typed) throws Exception {
        try (Connection db = DriverManager.getConnection(url)) {
            return restore(db, backendsUp, typed);
        }
    }

    private void assertAllUnderOriginalKey() throws Exception {
        CompanionCryptoService original = new CompanionCryptoService(OLD);
        Map<Long, String> rows = rows();
        assertEquals("synthetic-key-one", original.decrypt(rows.get(1L)));
        assertEquals("合成密钥二", original.decrypt(rows.get(2L)));
        assertNull(rows.get(3L));
        assertEquals("", rows.get(4L));
        assertEquals("synthetic-key-five", original.decrypt(rows.get(5L)));
    }

    @Test
    void restoreReturnsEveryRowToTheConfiguredKeyKeepsTheKeychainAndIsRepeatable() throws Exception {
        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));
        Map<String, String> keys = new LinkedHashMap<>(keychain.items);
        Map<Long, String> migrated = rows();

        assertTrue(restore(false, FunnelSecretPrep.RESTORE_CONFIRMATION));
        assertAllUnderOriginalKey();
        assertNotEquals(migrated.get(1L), rows().get(1L));
        assertEquals(keys, keychain.items);
        assertNoSecretPrinted();

        // Nothing left to do: no confirmation is asked for and nothing is rewritten.
        Map<Long, String> restored = rows();
        assertTrue(restore(false, null));
        assertEquals(restored, rows());
        assertEquals(keys, keychain.items);
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("无需恢复"));
    }

    @Test
    void restoreRefusesWhileABackendListensOrWithoutTheTypedConfirmation() throws Exception {
        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));
        Map<Long, String> migrated = rows();
        assertFalse(restore(true, FunnelSecretPrep.RESTORE_CONFIRMATION));
        assertFalse(restore(false, FunnelSecretPrep.CONFIRMATION));
        assertFalse(restore(false, null));
        assertEquals(migrated, rows());
    }

    @Test
    void restoreHandlesRowsSavedUnderEitherKey() throws Exception {
        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));
        // A key saved while the backend ran without the Keychain key is already under the original one.
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement insert = db.prepareStatement("INSERT INTO model_credentials VALUES (7, ?)")) {
            insert.setString(1, new CompanionCryptoService(OLD).encrypt("synthetic-key-seven"));
            insert.executeUpdate();
        }
        String untouched = rows().get(7L);
        assertTrue(restore(false, FunnelSecretPrep.RESTORE_CONFIRMATION));
        assertAllUnderOriginalKey();
        assertEquals(untouched, rows().get(7L));
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("原配置密钥可解 1，待恢复 3"));
    }

    @Test
    void restoreAbortsBeforeAnyWriteWhenARowIsUnreadableByBothKeys() throws Exception {
        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));
        try (Connection db = DriverManager.getConnection(url); Statement statement = db.createStatement()) {
            statement.execute("INSERT INTO model_credentials VALUES (9, '"
                    + new CompanionCryptoService("another-synthetic-secret").encrypt("lost") + "')");
        }
        Map<Long, String> before = rows();
        assertFalse(restore(false, FunnelSecretPrep.RESTORE_CONFIRMATION));
        assertEquals(before, rows());
    }

    @Test
    void aFailureMidRestoreRollsBackAndTheNextRunFinishes() throws Exception {
        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));
        Map<Long, String> migrated = rows();
        AtomicInteger updates = new AtomicInteger();
        try (Connection real = DriverManager.getConnection(url)) {
            Connection failing = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = invoke(real, method, args);
                        if (!method.getName().equals("prepareStatement")) return result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (p, m, a) -> {
                                    if (m.getName().equals("executeUpdate") && updates.incrementAndGet() == 2) {
                                        throw new SQLException("synthetic failure");
                                    }
                                    return invoke(result, m, a);
                                });
                    });
            assertThrows(SQLException.class, () -> restore(failing, false, FunnelSecretPrep.RESTORE_CONFIRMATION));
        }
        assertEquals(migrated, rows());
        assertTrue(restore(false, FunnelSecretPrep.RESTORE_CONFIRMATION));
        assertAllUnderOriginalKey();
    }

    @Test
    void restoreRefusesWhenTheConfiguredKeyIsBlankOrIsTheKeychainKey() throws Exception {
        assertTrue(run(true, false, FunnelSecretPrep.CONFIRMATION));
        Map<Long, String> migrated = rows();
        for (String configured : new String[]{"", keychain.items.get("companion.api-key.secret")}) {
            try (Connection db = DriverManager.getConnection(url)) {
                assertFalse(FunnelSecretPrep.restore(environment().withProperty("companion.api-key.secret", configured),
                        keychain, db, (host, port) -> false, () -> FunnelSecretPrep.RESTORE_CONFIRMATION,
                        new PrintStream(printed, true, StandardCharsets.UTF_8)));
            }
        }
        assertEquals(migrated, rows());
        assertNoSecretPrinted();
    }

    @Test
    void plainConfigurationResolvesWithoutTheFunnelProfile() {
        var environment = FunnelSecretPrep.resolve();
        assertNull(environment.getProperty("funnel.public-origin"));
        assertTrue(environment.getRequiredProperty("spring.datasource.url").startsWith("jdbc:"));
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }
}
