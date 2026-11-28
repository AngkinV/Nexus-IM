package com.nexus.chat.service;

import com.nexus.chat.model.*;
import com.nexus.chat.model.agent.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.repository.agent.*;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.domain.PageRequest;
import jakarta.persistence.EntityManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real in-memory H2/JPA schema, queries and tombstones. Never starts the application or touches its DB. */
class SkillsMemoryRepositoryTest {
    static SessionFactory factory;
    EntityManager em;
    JpaRepositoryFactory repos;
    @BeforeAll static void database() {
        var registry=new StandardServiceRegistryBuilder().applySettings(Map.of(
                "hibernate.connection.driver_class","org.h2.Driver",
                "hibernate.connection.url","jdbc:h2:mem:skills_memory_"+UUID.randomUUID()+";MODE=MySQL",
                "hibernate.hbm2ddl.auto","create-drop",
                "hibernate.show_sql","false")).build();
        factory=new MetadataSources(registry)
                .addAnnotatedClass(CompanionMemory.class).addAnnotatedClass(CompanionMessage.class).addAnnotatedClass(TavernSession.class)
                .addAnnotatedClass(CompanionSkill.class).addAnnotatedClass(CompanionSkillVersion.class).addAnnotatedClass(CompanionSkillBinding.class)
                .addAnnotatedClass(AgentLongMemory.class).addAnnotatedClass(AgentSessionSummary.class).addAnnotatedClass(AgentRunRecord.class)
                .buildMetadata().buildSessionFactory();
    }
    @AfterAll static void close(){if(factory!=null)factory.close();}
    @BeforeEach void start(){em=factory.createEntityManager();em.getTransaction().begin();repos=new JpaRepositoryFactory(em);}
    @AfterEach void stop(){if(em.getTransaction().isActive())em.getTransaction().rollback();em.close();}
    CompanionMemory memory(Long owner,Long role,String hash){var m=new CompanionMemory();m.setUserId(owner);m.setRoleId(role);m.setContent("a real source fixture");m.setSourceHash(hash);em.persist(m);em.flush();return m;}
    @Test void sourceQueriesCannotRecallAnotherAccountOrForgottenRows(){
        var repo=repos.getRepository(CompanionMemoryRepository.class);var own=memory(1L,2L,"hash-a");memory(9L,2L,"hash-b");
        assertEquals(List.of(own.getId()),repo.findActive(1L,2L,PageRequest.of(0,10)).stream().map(CompanionMemory::getId).toList());
        assertTrue(repo.existsByUserIdAndRoleIdAndSourceHash(1L,2L,"hash-a"));assertEquals(1,repo.countActive(1L,2L));
        repo.forgetAll(1L,2L);em.clear();assertTrue(repo.findActive(1L,2L,PageRequest.of(0,10)).isEmpty());
        assertEquals("",repo.findById(own.getId()).orElseThrow().getContent());assertTrue(repo.existsByUserIdAndRoleIdAndSourceHash(1L,2L,"hash-a"));
    }
    @Test void messageProgressAndForgetBarrierAreDurable(){
        var messages=repos.getRepository(CompanionMessageRepository.class);var sessions=repos.getRepository(TavernSessionRepository.class);
        TavernSession session=new TavernSession();session.setUserId(1L);session.setRoleId(2L);session.setRollingSummary("old");em.persist(session);em.flush();
        CompanionMessage source=new CompanionMessage();source.setUserId(1L);source.setRoleId(2L);source.setConversationId(6L);source.setSessionId(session.getId());source.setContent("我喜欢喝茶");em.persist(source);em.flush();
        assertEquals(1,messages.findPendingMemory(1L,2L,0L,CompanionMessage.SenderType.user,PageRequest.of(0,100)).size());
        assertEquals(1,sessions.invalidateSummaries(1L,2L,source.getId()));em.clear();
        var restored=sessions.findById(session.getId()).orElseThrow();assertNull(restored.getRollingSummary());assertEquals(source.getId(),restored.getSummaryResetThroughId());
        assertEquals(source.getId(),sessions.forgetBarrier(1L,2L));
    }
    @Test void workbenchSummaryAndCandidateQueriesArePartitioned(){
        var summaries=repos.getRepository(AgentSessionSummaryRepository.class);var memories=repos.getRepository(AgentLongMemoryRepository.class);
        AgentSessionSummary s=new AgentSessionSummary();s.setUserId(1L);s.setSessionId("session-a");s.setSummaryVersion(1);s.setCoveredFromMsgId(1L);s.setCoveredToMsgId(2L);s.setSummaryText("actual prior turns");s.setScopeKey("a".repeat(64));s.setSessionGeneration(1L);em.persist(s);
        AgentLongMemory m=new AgentLongMemory();m.setUserId(1L);m.setSourceSessionId("session-a");m.setSourceTraceId("run-a");m.setScopeKey(s.getScopeKey());m.setSessionGeneration(1L);m.setContent("explicit preference");m.setMemoryType("PREFERENCE");m.setConfidence(new java.math.BigDecimal("0.750"));em.persist(m);em.flush();
        assertTrue(summaries.findTopByUserIdAndSessionIdAndScopeKeyAndSessionGenerationOrderBySummaryVersionDesc(1L,"session-a",s.getScopeKey(),1).isPresent());
        assertTrue(summaries.findTopByUserIdAndSessionIdAndScopeKeyAndSessionGenerationOrderBySummaryVersionDesc(2L,"session-a",s.getScopeKey(),1).isEmpty());
        assertEquals(1,memories.findRecall(1L,"session-a",s.getScopeKey(),1,PageRequest.of(0,8)).size());assertTrue(memories.findRecall(1L,"session-a",s.getScopeKey(),2,PageRequest.of(0,8)).isEmpty());
    }
    @Test void suppressingSourceDoesNotOverwriteRunPayload(){
        var runs=repos.getRepository(AgentRunRecordRepository.class);
        var run=new AgentRunRecord();run.setUserId(1L);run.setSessionId("session-a");run.setRunId("run-source");run.setStatus("done");run.setStartedAt(java.time.Instant.now());run.setUpdatedAt(java.time.Instant.now());run.setPayloadJson("{\"input\":\"source\"}");em.persist(run);em.flush();
        assertEquals(1,runs.findPendingMemory(1L,"session-a",PageRequest.of(0,10)).size());runs.suppressMemorySource(1L,"session-a","run-source");em.clear();
        assertTrue(runs.findPendingMemory(1L,"session-a",PageRequest.of(0,10)).isEmpty());assertEquals("{\"input\":\"source\"}",runs.findById(run.getId()).orElseThrow().getPayloadJson());
    }
    @Test void allNewSkillRepositoriesBuildWithoutInvalidQueries(){
        assertNotNull(repos.getRepository(CompanionSkillRepository.class));assertNotNull(repos.getRepository(CompanionSkillVersionRepository.class));assertNotNull(repos.getRepository(CompanionSkillBindingRepository.class));
    }
}
