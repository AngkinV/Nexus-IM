package com.nexus.chat.service;

import com.nexus.chat.dto.UserPreferenceDTO;
import com.nexus.chat.model.UserPreference;
import com.nexus.chat.repository.UserPreferenceRepository;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real Hibernate transactions and constraints, isolated in H2 (not production MySQL). */
class UserPreferencePersistenceTest {
    SessionFactory sessions;
    JpaTransactionManager transactions;
    UserPreferenceRepository repository;
    UserPreferenceService service;

    @BeforeEach void setup() {
        sessions = new Configuration().addAnnotatedClass(UserPreference.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:settings_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.show_sql", "false")
                .setProperty("hibernate.connection.pool_size", "4")
                .buildSessionFactory();
        transactions = new JpaTransactionManager(sessions);
        UserPreferenceRepository raw = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(sessions))
                .getRepository(UserPreferenceRepository.class);
        // Apply the same persistence-exception translation that Spring applies
        // to @Repository beans in the real application; this programmatic
        // factory is intentionally not a full application context.
        org.springframework.aop.framework.ProxyFactory translated = new org.springframework.aop.framework.ProxyFactory(raw);
        translated.addAdvice(new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
                new org.springframework.orm.jpa.vendor.HibernateJpaDialect()));
        repository = (UserPreferenceRepository) translated.getProxy();
        service = new UserPreferenceService(repository, transactions);
    }
    @AfterEach void close() { sessions.close(); }

    @Test void actualCommittedRowsAreSeparatedByUser() {
        service.updatePreferences(7L, new UserPreferenceDTO("12", 40, false));
        UserPreferenceService recreated = new UserPreferenceService(repository, transactions);
        UserPreferenceDTO a = recreated.getPreferences(7L);
        UserPreferenceDTO b = recreated.getPreferences(8L);
        assertEquals("12", a.getHourCycle());
        assertEquals(40, a.getGlassIntensity());
        assertFalse(a.getSoundEnabled());
        assertEquals("24", b.getHourCycle());
        assertEquals(100, b.getGlassIntensity());
        assertTrue(b.getSoundEnabled());
    }

    // The delegate is a real JPA repository. This wrapper only aligns the first
    // two SELECT completions so the races are deterministic rather than lucky.
    UserPreferenceService simultaneousReaders() {
        CountDownLatch selected = new CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        UserPreferenceRepository aligned = (UserPreferenceRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{UserPreferenceRepository.class}, (proxy, method, arguments) -> {
                    Object result;
                    try { result = method.invoke(repository, arguments); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                    if (method.getName().equals("findByUserId") && reads.incrementAndGet() <= 2) {
                        selected.countDown();
                        if (!selected.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("SELECT barrier timed out");
                    }
                    return result;
                });
        return new UserPreferenceService(aligned, transactions);
    }

    void concurrent(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(a), second = pool.submit(b);
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS); }
    }

    @Test void twoFirstAccessesCommitOneUniqueUserRowAndBothReturn() throws Exception {
        UserPreferenceService aligned = simultaneousReaders();
        concurrent(() -> aligned.getPreferences(7L), () -> aligned.getPreferences(7L));
        assertEquals("24", service.getPreferences(7L).getHourCycle());
        try (var session = sessions.openSession()) {
            assertEquals(1L, session.createSelectionQuery("select count(p) from UserPreference p where p.userId=7", Long.class).getSingleResult());
        }
    }

    @Test void simultaneousPartialUpdatesDoNotRestoreStaleUnchangedColumns() throws Exception {
        service.getPreferences(7L);
        UserPreferenceService aligned = simultaneousReaders();
        concurrent(() -> aligned.updatePreferences(7L, new UserPreferenceDTO("12", null, null)),
                () -> aligned.updatePreferences(7L, new UserPreferenceDTO(null, 35, null)));
        UserPreferenceDTO stored = service.getPreferences(7L);
        assertEquals("12", stored.getHourCycle());
        assertEquals(35, stored.getGlassIntensity());
        assertTrue(stored.getSoundEnabled());
    }
}
