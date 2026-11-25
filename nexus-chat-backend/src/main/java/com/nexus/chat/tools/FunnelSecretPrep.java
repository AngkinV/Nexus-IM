package com.nexus.chat.tools;

import com.nexus.chat.config.FunnelSecretGuard;
import com.nexus.chat.service.CompanionCryptoService;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Offline preparation of the Funnel secrets. It resolves the same Spring configuration as
 * the backend without creating an application context, and never prints secret material.
 *
 * <p>{@code check} (default) only reads. {@code prepare} stores missing strong secrets in the
 * macOS Keychain and re-encrypts {@code model_credentials.api_key_encrypted} in one
 * transaction. Keychain entries are only ever added, never replaced, and they are written
 * before the database changes, so an interrupted run can always be repeated.
 *
 * <p>{@code restore} is the reverse for a database that stays local: rows under the Keychain key
 * go back under the configured key, so the backend starts again without injected environment
 * variables. It resolves the configuration of a plain start (no extra profiles) and leaves the
 * Keychain untouched.
 */
public final class FunnelSecretPrep {
    static final String SERVICE = "nexus-funnel";
    static final String COMPANION = "companion.api-key.secret";
    static final String CONFIRMATION = "MIGRATE";
    static final String RESTORE_CONFIRMATION = "RESTORE";
    /** What is being worked on, as setting, Keychain item and column names only: all a failure may say. */
    private static String step = "解析 Spring 配置";

    interface Keychain {
        Optional<String> find(String account) throws Exception;

        void add(String account, String value) throws Exception;
    }

    public static void main(String[] args) {
        try {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                    .setLevel(ch.qos.logback.classic.Level.OFF);
            command(args);
        } catch (Exception failure) {
            // SQL/configuration/native failures can contain private URLs or values.
            reportFailure(failure, System.err);
            System.exit(1);
        }
    }

    static void reportFailure(Exception failure, PrintStream out) {
        out.println("准备工具失败 (" + failure.getClass().getSimpleName() + ")，所在步骤: " + step
                + "。未输出异常内容；请核对安全配置与数据库/Keychain可用性。");
    }

    private static void command(String[] args) throws Exception {
        String mode = args.length == 0 ? "check" : args[0];
        if (args.length > 1 || !List.of("check", "prepare", "restore").contains(mode)) {
            System.err.println("用法: FunnelSecretPrep [check|prepare|restore]");
            System.exit(2);
        }
        boolean restore = mode.equals("restore");
        ConfigurableEnvironment environment = restore ? resolve() : resolveEnvironment();
        step = "连接数据库 (spring.datasource.url / username / password)";
        // The backend URL may create a missing database; this tool must not.
        String url = environment.getRequiredProperty("spring.datasource.url")
                .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
        boolean ready;
        try (Connection db = DriverManager.getConnection(url, environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"))) {
            Supplier<String> typed = () -> {
                try {
                    return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                } catch (IOException closed) {
                    return null;
                }
            };
            ready = restore
                    ? restore(environment, new MacKeychain(), db, FunnelSecretPrep::listening, typed, System.out)
                    : run(mode.equals("prepare"), environment, new MacKeychain(), db, FunnelSecretPrep::listening, typed, System.out);
        }
        System.exit(ready ? 0 : 1);
    }

    /** Spring's own config-data loading (files, imports, profiles, environment), no context. */
    static ConfigurableEnvironment resolveEnvironment() {
        return resolve("local", "funnel");
    }

    /** With no profiles this is the configuration of a plain {@code mvn spring-boot:run}. */
    static ConfigurableEnvironment resolve(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(), new DefaultBootstrapContext(),
                profiles);
        return environment;
    }

    static boolean run(boolean prepare, ConfigurableEnvironment environment, Keychain keychain, Connection db,
                       BiPredicate<String, Integer> listening, Supplier<String> confirmation, PrintStream out) throws Exception {
        step = "按公开门禁核对 " + String.join(", ", FunnelSecretGuard.REQUIRED);
        List<String> configInvalid = FunnelSecretGuard.invalidKeys(environment);
        String configCompanion = environment.getProperty(COMPANION, "");
        if (configInvalid.contains("jwt.secret")) {
            out.println("拒绝执行: jwt.secret 不满足公开门禁；本工具不生成、覆盖或轮换 JWT。未做任何修改。");
            return false;
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        for (String key : FunnelSecretGuard.REQUIRED) {
            if (key.equals("jwt.secret")) continue;
            step = "读取 Keychain 项 " + key;
            Optional<String> value = keychain.find(key);
            if (value.isPresent()) stored.put(key, value.get());
        }
        environment.getPropertySources().addFirst(new MapPropertySource("candidate-keychain", new LinkedHashMap<>(stored)));
        List<String> badStored = FunnelSecretGuard.invalidKeys(environment).stream().filter(stored::containsKey).toList();
        if (!badStored.isEmpty()) {
            out.println("拒绝执行: 已有 Keychain 项不满足公开门禁: " + String.join(", ", badStored)
                    + "。不覆盖它们，数据库未改动。");
            return false;
        }
        step = "读取配置 agent.python.base-url / server.port";
        URI python = URI.create(environment.getRequiredProperty("agent.python.base-url"));
        boolean javaUp = listening.test("127.0.0.1", environment.getRequiredProperty("server.port", Integer.class));
        boolean pythonUp = listening.test(python.getHost(), python.getPort());
        out.println("后端: Java " + (javaUp ? "在监听" : "未监听") + "，Python " + (pythonUp ? "在监听" : "未监听"));

        if (prepare) {
            if (javaUp || pythonUp) {
                out.println("拒绝执行: 请先停止 Java 和 Python 后端。未做任何修改。");
                return false;
            }
            out.println("将把缺失的强密钥写入 Keychain，并在一个事务内重新加密已保存的模型凭据。输入 " + CONFIRMATION + " 继续:");
            if (!CONFIRMATION.equals(confirmation.get())) {
                out.println("未确认。未做任何修改。");
                return false;
            }
        }

        step = "读取 model_credentials.api_key_encrypted";
        db.setReadOnly(!prepare);
        db.setAutoCommit(false);
        // Before anything is generated, the key the rows must end up under may not exist yet.
        String knownTarget = stored.containsKey(COMPANION) ? (String) stored.get(COMPANION)
                : configInvalid.contains(COMPANION) ? null : configCompanion;
        Map<Long, String> plaintexts = new LinkedHashMap<>();
        Map<Long, String> pending = new LinkedHashMap<>();
        int unreadable = 0;
        for (Map.Entry<Long, String> row : ciphertexts(db, prepare).entrySet()) {
            Optional<String> current = knownTarget == null ? Optional.empty() : decrypt(knownTarget, row.getValue());
            Optional<String> plain = current.isPresent() ? current : decrypt(configCompanion, row.getValue());
            if (plain.isEmpty()) {
                unreadable++;
                continue;
            }
            plaintexts.put(row.getKey(), plain.get());
            if (current.isEmpty()) pending.put(row.getKey(), row.getValue());
        }
        out.println("已加密模型凭据: " + (plaintexts.size() + unreadable) + " 条，已在目标密钥下 "
                + (plaintexts.size() - pending.size()) + "，待迁移 " + pending.size() + "，无法解密 " + unreadable);

        if (unreadable > 0) {
            out.println("存在用配置密钥和 Keychain 密钥都无法解密的记录，需要先找回加密它们的原密钥。未做任何修改。");
            return false;
        }

        // In check mode too: otherwise check would send the user to a prepare that refuses.
        if (!pending.isEmpty() && configCompanion.isBlank()) {
            db.rollback();
            out.println("拒绝执行: 原加密密钥配置为空，需确认实际原密钥后才能归档与迁移。未做任何修改。");
            return false;
        }

        if (prepare) {
            for (String key : FunnelSecretGuard.REQUIRED) {
                if (key.equals("jwt.secret") || stored.containsKey(key) || !configInvalid.contains(key)) continue;
                // Hex cannot collide with the guard's development-marker patterns.
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                String value = HexFormat.of().formatHex(random);
                step = "写入并回读 Keychain 项 " + key;
                store(keychain, key, value);
                stored.put(key, value);
                out.println("Keychain 新增: " + key);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("prepared-keychain", new LinkedHashMap<>(stored)));
            if (!FunnelSecretGuard.invalidKeys(environment).isEmpty()) {
                db.rollback();
                out.println("拒绝迁移: 目标安全配置未通过同一门禁，数据库未改动。");
                return false;
            }
            if (!pending.isEmpty()) {
                String target = (String) stored.get(COMPANION);
                // Keep the key that old backups are encrypted with. Encoded, because it is arbitrary text.
                // The account name carries a fingerprint of that key, so the step does not name it.
                step = "归档旧 " + COMPANION + " 到 Keychain";
                String archive = COMPANION + ".old." + fingerprint(configCompanion);
                if (keychain.find(archive).isEmpty()) {
                    store(keychain, archive, Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(configCompanion.getBytes(StandardCharsets.UTF_8)));
                    out.println("Keychain 已归档旧密钥，归档标识和值不输出。");
                }
                step = "更新并回读 model_credentials.api_key_encrypted";
                try {
                    CompanionCryptoService crypto = new CompanionCryptoService(target);
                    try (PreparedStatement update = db.prepareStatement(
                            "UPDATE model_credentials SET api_key_encrypted = ? WHERE id = ? AND api_key_encrypted = ?")) {
                        for (Map.Entry<Long, String> row : pending.entrySet()) {
                            update.setString(1, crypto.encrypt(plaintexts.get(row.getKey())));
                            update.setLong(2, row.getKey());
                            update.setString(3, row.getValue());
                            if (update.executeUpdate() != 1) throw new IllegalStateException("记录在迁移期间被改动");
                        }
                    }
                    Map<Long, String> written = ciphertexts(db, true);
                    if (!written.keySet().equals(plaintexts.keySet())) throw new IllegalStateException("记录在迁移期间被改动");
                    for (Map.Entry<Long, String> row : written.entrySet()) {
                        if (!decrypt(target, row.getValue()).equals(Optional.of(plaintexts.get(row.getKey())))) {
                            throw new IllegalStateException("迁移后校验失败");
                        }
                    }
                    db.commit();
                } catch (SQLException | RuntimeException failure) {
                    db.rollback();
                    out.println("迁移失败，数据库已回滚；Keychain 中的新旧密钥都保留，修复后可重新执行 prepare。");
                    throw failure;
                }
                out.println("已迁移 " + pending.size() + " 条并提交。");
                pending.clear();
            }
        }

        environment.getPropertySources().addFirst(new MapPropertySource("keychain", stored));
        List<String> invalid = FunnelSecretGuard.invalidKeys(environment);
        List<String> fromKeychain = new ArrayList<>(stored.keySet());
        out.println("来自 Keychain 的密钥: " + (fromKeychain.isEmpty() ? "无" : String.join(", ", fromKeychain)));
        out.println("仍不满足公开门禁的密钥: " + (invalid.isEmpty() ? "无" : String.join(", ", invalid)));
        boolean ready = invalid.isEmpty() && pending.isEmpty();
        out.println(ready ? "结论: 密钥与已加密数据就绪（启动时须按文档从 Keychain 注入环境变量）。"
                : "结论: 未达到主机直连公网的门禁。这个做法已停用，不需要处理：本地开发照常用原命令启动；"
                + "不要为此执行 prepare，它会让原启动命令解不开模型凭据。公网环境见 docs/PUBLIC-ISOLATION-PLAN.md。");
        return ready;
    }

    static boolean restore(ConfigurableEnvironment environment, Keychain keychain, Connection db,
                           BiPredicate<String, Integer> listening, Supplier<String> confirmation, PrintStream out) throws Exception {
        step = "读取配置 " + COMPANION;
        String original = environment.getProperty(COMPANION, "");
        if (original.isBlank()) {
            out.println("拒绝执行: 配置中的原加密密钥为空，无法确定要恢复到哪把密钥。未做任何修改。");
            return false;
        }
        step = "读取 Keychain 项 " + COMPANION;
        Optional<String> migrated = keychain.find(COMPANION);
        if (migrated.equals(Optional.of(original))) {
            // An injected environment variable would make "restoring" a no-op that looks like success.
            out.println("拒绝执行: 当前配置的加密密钥与 Keychain 中的相同，请在没有注入 COMPANION_API_KEY_SECRET 的普通终端里执行。未做任何修改。");
            return false;
        }
        step = "读取配置 agent.python.base-url / server.port";
        URI python = URI.create(environment.getRequiredProperty("agent.python.base-url"));
        boolean javaUp = listening.test("127.0.0.1", environment.getRequiredProperty("server.port", Integer.class));
        boolean pythonUp = listening.test(python.getHost(), python.getPort());
        out.println("后端: Java " + (javaUp ? "在监听" : "未监听") + "，Python " + (pythonUp ? "在监听" : "未监听"));
        if (javaUp || pythonUp) {
            out.println("拒绝执行: 请先停止 Java 和 Python 后端。未做任何修改。");
            return false;
        }

        step = "读取 model_credentials.api_key_encrypted";
        db.setAutoCommit(false);
        Map<Long, String> plaintexts = new LinkedHashMap<>();
        Map<Long, String> pending = new LinkedHashMap<>();
        int unreadable = 0;
        for (Map.Entry<Long, String> row : ciphertexts(db, true).entrySet()) {
            Optional<String> plain = decrypt(original, row.getValue());
            if (plain.isEmpty() && migrated.isPresent()) {
                plain = decrypt(migrated.get(), row.getValue());
                if (plain.isPresent()) pending.put(row.getKey(), row.getValue());
            }
            if (plain.isEmpty()) unreadable++;
            else plaintexts.put(row.getKey(), plain.get());
        }
        out.println("已加密模型凭据: " + (plaintexts.size() + unreadable) + " 条，原配置密钥可解 "
                + (plaintexts.size() - pending.size()) + "，待恢复 " + pending.size() + "，无法解密 " + unreadable);
        if (unreadable > 0) {
            db.rollback();
            out.println("存在用原配置密钥和 Keychain 密钥都无法解密的记录。未做任何修改。");
            return false;
        }
        if (pending.isEmpty()) {
            db.rollback();
            out.println("结论: 无需恢复，全部模型凭据都能用原配置密钥解密，可以用原来的命令启动 Java。");
            return true;
        }
        out.println("将在一个事务内把 " + pending.size() + " 条模型凭据换回原配置密钥，Keychain 不改动。输入 "
                + RESTORE_CONFIRMATION + " 继续:");
        if (!RESTORE_CONFIRMATION.equals(confirmation.get())) {
            db.rollback();
            out.println("未确认。未做任何修改。");
            return false;
        }
        step = "更新并回读 model_credentials.api_key_encrypted";
        try {
            CompanionCryptoService crypto = new CompanionCryptoService(original);
            try (PreparedStatement update = db.prepareStatement(
                    "UPDATE model_credentials SET api_key_encrypted = ? WHERE id = ? AND api_key_encrypted = ?")) {
                for (Map.Entry<Long, String> row : pending.entrySet()) {
                    update.setString(1, crypto.encrypt(plaintexts.get(row.getKey())));
                    update.setLong(2, row.getKey());
                    update.setString(3, row.getValue());
                    if (update.executeUpdate() != 1) throw new IllegalStateException("记录在恢复期间被改动");
                }
            }
            Map<Long, String> written = ciphertexts(db, true);
            if (!written.keySet().equals(plaintexts.keySet())) throw new IllegalStateException("记录在恢复期间被改动");
            for (Map.Entry<Long, String> row : written.entrySet()) {
                if (!decrypt(original, row.getValue()).equals(Optional.of(plaintexts.get(row.getKey())))) {
                    throw new IllegalStateException("恢复后校验失败");
                }
            }
            db.commit();
        } catch (SQLException | RuntimeException failure) {
            db.rollback();
            out.println("恢复失败，数据库已回滚；Keychain 未改动，修复后可重新执行 restore。");
            throw failure;
        }
        out.println("已恢复 " + pending.size() + " 条并提交。");
        out.println("结论: 全部模型凭据都能用原配置密钥解密，可以用原来的命令启动 Java。");
        return true;
    }

    private static Map<Long, String> ciphertexts(Connection db, boolean lock) throws SQLException {
        Map<Long, String> rows = new LinkedHashMap<>();
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT id, api_key_encrypted FROM model_credentials WHERE api_key_encrypted IS NOT NULL ORDER BY id"
                        + (lock ? " FOR UPDATE" : ""))) {
            while (result.next()) {
                // The backend treats blank the same as "no key saved".
                if (!result.getString(2).isBlank()) rows.put(result.getLong(1), result.getString(2));
            }
        }
        return rows;
    }

    private static Optional<String> decrypt(String secret, String ciphertext) {
        try {
            return Optional.of(new CompanionCryptoService(secret).decrypt(ciphertext));
        } catch (RuntimeException wrongKeyOrCorrupt) {
            return Optional.empty();
        }
    }

    private static void store(Keychain keychain, String account, String value) throws Exception {
        keychain.add(account, value);
        if (!keychain.find(account).equals(Optional.of(value))) {
            throw new IllegalStateException("Keychain 写入后回读不一致: " + account + "。数据库未改动。");
        }
    }

    /** Salted so the id reveals nothing about the AES key, which is itself SHA-256 of the secret. */
    private static String fingerprint(String secret) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("nexus-funnel-key-id/" + secret).getBytes(StandardCharsets.UTF_8)), 0, 4);
    }

    private static boolean listening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException closed) {
            return false;
        }
    }

    private static final class MacKeychain implements Keychain {
        @Override
        public Optional<String> find(String account) throws Exception {
            Process process = new ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
            int exit = process.waitFor();
            if (exit == 44) return Optional.empty(); // errSecItemNotFound
            if (exit != 0) throw new IllegalStateException("Keychain 读取失败 (exit " + exit + "): " + account);
            return Optional.of(value);
        }

        @Override
        public void add(String account, String value) throws Exception {
            // Interactive mode keeps the value out of the process argument list. Without -U an
            // existing item is left untouched; the caller verifies by reading back.
            Process process = new ProcessBuilder("/usr/bin/security", "-i")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream commands = process.getOutputStream()) {
                commands.write(("add-generic-password -s " + SERVICE + " -a " + account + " -w " + value + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            process.waitFor();
        }
    }

    private FunnelSecretPrep() {
    }
}
