package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionMessage.SenderType;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The seat list's preview queries on real Hibernate, isolated in H2 (not production MySQL). */
class CompanionMessageRepositoryTest {
    private static final long USER = 7L;
    private static final long STRANGER = 8L;
    private static final long IRIS = 3L;
    private static final long NOOR = 4L;
    private static final long STRANGERS_ROLE = 9L;

    SessionFactory sessions;
    CompanionMessageRepository repository;

    @BeforeEach
    void setUp() {
        sessions = new Configuration().addAnnotatedClass(CompanionMessage.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url",
                        "jdbc:h2:mem:companion_messages_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.show_sql", "false")
                .buildSessionFactory();
        repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(sessions))
                .getRepository(CompanionMessageRepository.class);
    }

    @AfterEach
    void close() {
        sessions.close();
    }

    @Test
    void theListGetsEachCharactersNewestLineTogetherWithThatLinesOwnSender() {
        said(USER, IRIS, SenderType.user, "晚上好");
        said(USER, IRIS, SenderType.role, "（她抬起头。）坐吧。");
        said(USER, NOOR, SenderType.role, "又是你。");
        said(USER, NOOR, SenderType.user, "那明天见");
        // Another account's conversation, written after all of the above.
        said(STRANGER, STRANGERS_ROLE, SenderType.role, "欢迎。");

        assertEquals(Map.of(IRIS, "role: （她抬起头。）坐吧。", NOOR, "user: 那明天见"),
                linesByRole(repository.findLatestPerRole(USER)));
        assertEquals(Map.of(STRANGERS_ROLE, "role: 欢迎。"), linesByRole(repository.findLatestPerRole(STRANGER)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t", "\r\n", " \t\r\n "})
    void aNewerLineWithNothingInItDoesNotHideTheLastOneThatSaysSomething(String nothing) {
        said(USER, IRIS, SenderType.role, "（她抬起头。）坐吧。");
        said(USER, IRIS, SenderType.user, nothing);
        // The second character has only ever been sent nothing: there is no line to show for her.
        said(USER, NOOR, SenderType.user, nothing);

        // The sender is the one who said the line shown, not whoever wrote last.
        assertEquals(Map.of(IRIS, "role: （她抬起头。）坐吧。"), linesByRole(repository.findLatestPerRole(USER)));
        assertEquals(Optional.of("role: （她抬起头。）坐吧。"),
                repository.findLatestForRole(USER, IRIS).map(CompanionMessageRepositoryTest::line));
        assertEquals(Optional.empty(), repository.findLatestForRole(USER, NOOR));
    }

    @Test
    void oneCharacterOnItsOwnGetsTheSameMessageTheListShows() {
        said(USER, IRIS, SenderType.user, "晚上好");
        said(USER, NOOR, SenderType.role, "又是你。");
        said(USER, IRIS, SenderType.role, "（她抬起头。）坐吧。");
        said(USER, NOOR, SenderType.user, "那明天见");
        said(STRANGER, STRANGERS_ROLE, SenderType.role, "欢迎。");

        List<CompanionMessage> list = repository.findLatestPerRole(USER);

        assertEquals(2, list.size());
        for (CompanionMessage shown : list) {
            assertEquals(Optional.of(shown.getId()),
                    repository.findLatestForRole(USER, shown.getRoleId()).map(CompanionMessage::getId));
        }
        assertEquals(Optional.empty(), repository.findLatestForRole(USER, 99L), "never spoken to");
        assertEquals(Optional.empty(), repository.findLatestForRole(USER, STRANGERS_ROLE), "someone else's conversation");
    }

    private void said(long userId, long roleId, SenderType sender, String content) {
        CompanionMessage message = new CompanionMessage();
        message.setConversationId(roleId); // one conversation per character; the entity maps no foreign key
        message.setUserId(userId);
        message.setRoleId(roleId);
        message.setSenderType(sender);
        message.setContent(content);
        sessions.inTransaction(session -> session.persist(message));
    }

    private static String line(CompanionMessage message) {
        return message.getSenderType() + ": " + message.getContent();
    }

    /** Fails on a second row for the same character, so "one line each" is checked on the way. */
    private static Map<Long, String> linesByRole(List<CompanionMessage> messages) {
        return messages.stream().collect(Collectors.toMap(CompanionMessage::getRoleId, CompanionMessageRepositoryTest::line));
    }
}
