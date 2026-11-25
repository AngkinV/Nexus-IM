package com.nexus.chat.service;

import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.InviteCode;
import com.nexus.chat.repository.InviteCodeRepository;
import com.nexus.chat.tools.InviteCodeTool;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Real Hibernate transactions on the schema Hibernate creates, isolated in H2. Synthetic codes only. */
class RegistrationPolicyTest {
    String url;
    SessionFactory sessions;
    TransactionTemplate tx;
    InviteCodeRepository repository;

    @BeforeEach void setup() {
        url = "jdbc:h2:mem:invites_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000";
        sessions = new Configuration().addAnnotatedClass(InviteCode.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.show_sql", "false")
                .setProperty("hibernate.connection.pool_size", "8")
                .buildSessionFactory();
        tx = new TransactionTemplate(new JpaTransactionManager(sessions));
        repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(sessions))
                .getRepository(InviteCodeRepository.class);
    }

    @AfterEach void close() { sessions.close(); }

    RegistrationPolicy invitesRequired() { return new RegistrationPolicy(repository, true, false); }

    long invite(String code, int maxUses, LocalDateTime expiresAt, boolean revoked) {
        return tx.execute(status -> {
            InviteCode invite = new InviteCode();
            invite.setCodeHash(InviteCode.hash(code));
            invite.setMaxUses(maxUses);
            invite.setExpiresAt(expiresAt);
            invite.setRevoked(revoked);
            return repository.save(invite).getId();
        });
    }

    int used(long id) { return tx.execute(status -> repository.findById(id).orElseThrow().getUsedCount()); }

    void consume(RegistrationPolicy policy, String code) { tx.executeWithoutResult(status -> policy.consumeInvite(code)); }

    void assertRejected(RegistrationPolicy policy, String code) {
        assertEquals(RegistrationPolicy.INVITE_REJECTED,
                assertThrows(BusinessException.class, () -> consume(policy, code)).getMessage());
    }

    @Test void theDefaultsAskForNoInviteAndKeepEmailCodes() {
        RegistrationPolicy policy = new RegistrationPolicy(repository, false, true);
        assertFalse(policy.isInviteRequired());
        assertTrue(policy.isEmailCodeRequired());
        consume(policy, null);
        policy.requireEmailCodeAllowed(null);
        assertEquals(0L, (long) tx.execute(status -> repository.count()));
    }

    @Test void everyUnusableCodeIsRejectedTheSameWayAndNothingIsConsumed() {
        long expired = invite("EXPIRED-CODE", 5, LocalDateTime.now().minusMinutes(1), false);
        long usedUp = invite("USEDUP-CODE", 1, null, false);
        long revoked = invite("REVOKED-CODE", 5, null, true);
        RegistrationPolicy policy = invitesRequired();
        consume(policy, "USEDUP-CODE");

        for (String code : new String[]{null, "", "UNKNOWN-CODE", "EXPIRED-CODE", "USEDUP-CODE", "REVOKED-CODE"}) {
            assertRejected(policy, code);
        }
        assertEquals(0, used(expired));
        assertEquals(1, used(usedUp));
        assertEquals(0, used(revoked));
    }

    @Test void aCodeIsAcceptedHoweverItIsTypedAndCountsEachUse() {
        long id = invite("ABCD-EFGH", 2, LocalDateTime.now().plusDays(1), false);
        RegistrationPolicy policy = invitesRequired();
        consume(policy, "abcd efgh");
        consume(policy, " ABCDEFGH ");
        assertEquals(2, used(id));
        assertRejected(policy, "ABCD-EFGH");
    }

    @Test void aRegistrationThatFailsAfterwardsGivesTheUseBack() {
        long id = invite("ROLLBACK-CODE", 1, null, false);
        RegistrationPolicy policy = invitesRequired();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            policy.consumeInvite("ROLLBACK-CODE");
            throw new IllegalStateException("username already exists");
        }));
        assertEquals(0, used(id));
        consume(policy, "ROLLBACK-CODE");
        assertEquals(1, used(id));
    }

    @Test void concurrentRegistrationsCannotTakeMoreUsesThanTheCodeHas() throws Exception {
        long id = invite("RACE-CODE", 3, null, false);
        RegistrationPolicy policy = invitesRequired();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    try {
                        consume(policy, "RACE-CODE");
                        return true;
                    } catch (BusinessException noUseLeft) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> attempt : attempts) if (attempt.get()) accepted++;
            assertEquals(3, accepted);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3, used(id));
    }

    @Test void emailCodesNeedAUsableInviteButDoNotConsumeIt() {
        long id = invite("MAIL-CODE", 1, null, false);
        RegistrationPolicy policy = new RegistrationPolicy(repository, true, true);
        for (String code : new String[]{null, "UNKNOWN-CODE"}) {
            assertEquals(RegistrationPolicy.INVITE_REJECTED,
                    assertThrows(BusinessException.class, () -> policy.requireEmailCodeAllowed(code)).getMessage());
        }
        policy.requireEmailCodeAllowed("mail-code");
        assertEquals(0, used(id));

        assertEquals(RegistrationPolicy.EMAIL_CODE_DISABLED,
                assertThrows(BusinessException.class, () -> invitesRequired().requireEmailCodeAllowed("MAIL-CODE")).getMessage());
    }

    @Test void theOperatorToolIssuesListsAndRevokesCodesWithoutStoringThem() throws Exception {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
        RegistrationPolicy policy = invitesRequired();
        try (Connection db = DriverManager.getConnection(url)) {
            String code = InviteCodeTool.create(db, 2, 7, "synthetic friend", new SecureRandom());
            assertTrue(code.matches("([A-HJKMNP-Z2-9]{4}-){5}[A-HJKMNP-Z2-9]{4}"), code);
            assertNotEquals(code, InviteCodeTool.create(db, 1, null, null, new SecureRandom()));

            consume(policy, code.toLowerCase());
            assertTrue(InviteCodeTool.run(new String[]{"list"}, db, out));
            String listing = printed.toString(StandardCharsets.UTF_8);
            assertTrue(listing.contains("已用 1/2") && listing.contains("synthetic friend") && listing.contains("不过期"));
            assertFalse(listing.contains(code));
            try (Statement statement = db.createStatement();
                 var rows = statement.executeQuery("SELECT code_hash FROM invite_codes")) {
                while (rows.next()) assertFalse(rows.getString(1).contains(code.replace("-", "")));
            }

            assertTrue(InviteCodeTool.run(new String[]{"revoke", "1"}, db, out));
            assertRejected(policy, code);
            assertFalse(InviteCodeTool.run(new String[]{"revoke", "999"}, db, out));

            assertTrue(InviteCodeTool.run(new String[]{"create", "--uses", "3", "--days", "30"}, db, out));
            for (String[] bad : new String[][]{{}, {"create", "--uses"}, {"create", "--uses", "0"}, {"create", "--days", "x"},
                    {"create", "--what", "1"}, {"revoke"}, {"revoke", "x"}, {"delete", "1"}}) {
                assertFalse(InviteCodeTool.run(bad, db, out));
            }
            assertEquals(3L, (long) tx.execute(status -> repository.count()));
        }
    }

    @Test void theToolSaysSoWhenTheBackendHasNotCreatedTheTableYet() throws Exception {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        try (Connection empty = DriverManager.getConnection("jdbc:h2:mem:empty_" + UUID.randomUUID())) {
            assertFalse(InviteCodeTool.run(new String[]{"create"}, empty, new PrintStream(printed, true, StandardCharsets.UTF_8)));
        }
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("invite_codes"));
    }
}
