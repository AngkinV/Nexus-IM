package com.nexus.chat.tools;

import com.nexus.chat.model.InviteCode;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.PrintStream;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Issues registration invites from the command line; there is no global administrator
 * role to do it in the application. It uses the backend's own configuration (including
 * {@code SPRING_PROFILES_ACTIVE}) without starting it. Only the hash of a code is stored,
 * so a code is shown once, when it is created.
 */
public final class InviteCodeTool {
    /** No 0/O, 1/I/L: codes get read aloud and typed. 24 symbols of 31 is about 118 bits. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 24;
    private static final String USAGE = "用法: InviteCodeTool create [--uses 次数] [--days 有效天数] [--note 备注] | list | revoke <id>";

    public static void main(String[] args) {
        try {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                    .setLevel(ch.qos.logback.classic.Level.OFF);
            ConfigurableEnvironment environment = FunnelSecretPrep.resolve();
            // The backend URL may create a missing database; this tool must not.
            String url = environment.getRequiredProperty("spring.datasource.url")
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean done;
            try (Connection db = DriverManager.getConnection(url, environment.getProperty("spring.datasource.username"),
                    environment.getProperty("spring.datasource.password"))) {
                done = run(args, db, System.out);
            }
            System.exit(done ? 0 : 1);
        } catch (Exception failure) {
            // Configuration and SQL failures can carry private URLs or values.
            System.err.println("邀请码工具失败 (" + failure.getClass().getSimpleName() + ")，未输出异常内容。");
            System.exit(1);
        }
    }

    public static boolean run(String[] args, Connection db, PrintStream out) throws SQLException {
        try (Statement probe = db.createStatement()) {
            probe.executeQuery("SELECT 1 FROM invite_codes WHERE 1 = 0").close();
        } catch (SQLException missing) {
            out.println("表 invite_codes 还不存在：它由后端在第一次启动时创建，请先让后端启动一次。");
            return false;
        }
        try {
            if (args.length == 1 && args[0].equals("list")) {
                list(db, out);
                return true;
            }
            if (args.length == 2 && args[0].equals("revoke")) {
                boolean revoked = revoke(db, Long.parseLong(args[1]));
                out.println(revoked ? "已作废。" : "没有这个编号。");
                return revoked;
            }
            if (args.length >= 1 && args[0].equals("create") && args.length % 2 == 1) {
                int uses = 1;
                Integer days = null;
                String note = null;
                for (int i = 1; i < args.length; i += 2) {
                    switch (args[i]) {
                        case "--uses" -> uses = Integer.parseInt(args[i + 1]);
                        case "--days" -> days = Integer.parseInt(args[i + 1]);
                        case "--note" -> note = args[i + 1];
                        default -> throw new IllegalArgumentException();
                    }
                }
                String code = create(db, uses, days, note, new SecureRandom());
                out.println("邀请码（只显示这一次）: " + code);
                out.println("可用 " + uses + " 次，" + (days == null ? "不过期" : days + " 天内有效") + "。");
                return true;
            }
        } catch (IllegalArgumentException badArguments) {
            // Falls through to the usage line.
        }
        out.println(USAGE);
        return false;
    }

    public static String create(Connection db, int uses, Integer days, String note, SecureRandom random) throws SQLException {
        if (uses < 1 || uses > 1000 || (days != null && (days < 1 || days > 365)) || (note != null && note.length() > 200)) {
            throw new IllegalArgumentException();
        }
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % 4 == 0) code.append('-');
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        LocalDateTime now = LocalDateTime.now();
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO invite_codes"
                + " (code_hash, max_uses, used_count, expires_at, revoked, note, created_at) VALUES (?, ?, 0, ?, ?, ?, ?)")) {
            insert.setString(1, InviteCode.hash(code.toString()));
            insert.setInt(2, uses);
            insert.setTimestamp(3, days == null ? null : Timestamp.valueOf(now.plusDays(days)));
            insert.setBoolean(4, false);
            insert.setString(5, note);
            insert.setTimestamp(6, Timestamp.valueOf(now));
            insert.executeUpdate();
        }
        return code.toString();
    }

    public static void list(Connection db, PrintStream out) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT id, note, used_count, max_uses, expires_at, revoked FROM invite_codes ORDER BY id")) {
            int count = 0;
            while (rows.next()) {
                count++;
                Timestamp expires = rows.getTimestamp(5);
                out.println("#" + rows.getLong(1) + "  已用 " + rows.getInt(3) + "/" + rows.getInt(4)
                        + "  到期 " + (expires == null ? "不过期" : expires.toLocalDateTime().toString())
                        + "  " + (rows.getBoolean(6) ? "已作废" : "有效")
                        + (rows.getString(2) == null ? "" : "  备注: " + rows.getString(2)));
            }
            if (count == 0) out.println("还没有邀请码。");
        }
    }

    public static boolean revoke(Connection db, long id) throws SQLException {
        try (PreparedStatement update = db.prepareStatement("UPDATE invite_codes SET revoked = ? WHERE id = ?")) {
            update.setBoolean(1, true);
            update.setLong(2, id);
            return update.executeUpdate() == 1;
        }
    }

    private InviteCodeTool() {
    }
}
