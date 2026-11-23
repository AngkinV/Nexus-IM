package com.nexus.chat.service.agent;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunConcurrencyTest {
    private static final String SID="as_test", RUN="run_race_001";
    private final AgentWorkbenchTestStore store=new AgentWorkbenchTestStore();
    private final AgentRunService service=store.runService();
    private void begin(){ service.begin(7L,SID,RUN,AgentRunServiceTest.request()); store.grant(service,RUN); }
    private void frame(String event,String attempt,int seq,Map<String,Object> fields){
        Map<String,Object> data=new LinkedHashMap<>(fields); data.put("attemptId",attempt); data.put("sequence",seq);
        service.record(7L,SID,RUN,event,data);
    }
    private Map<String,Object> live(){return service.list(7L,SID).get(0);}
    @Test void activeLeaseVersusRestart(){
        begin(); assertEquals("running",live().get("status"));
        assertEquals("interrupted",store.runService().list(7L,SID).get(0).get("status"));
    }
    @Test void retiredAttemptCannotOccupyResumeWindow(){
        begin(); frame("meta","A",1,Map.of()); frame("delta","A",2,Map.of("text","a"));
        service.resume(7L,SID,RUN,"b"); frame("meta","B",1,Map.of("traceId","b","resumed",true));
        frame("delta","B",2,Map.of("text","b")); service.resume(7L,SID,RUN,"c");
        frame("delta","unknown",1,Map.of("text","unapproved"));
        frame("delta","A",8,Map.of("text","poison")); frame("error","A",9,Map.of("scope","run"));
        frame("done","A",10,Map.of("finishReason","stop"));
        assertEquals("B",live().get("attemptId")); assertEquals("b",live().get("answer"));
        assertEquals("running",live().get("status")); assertNull(live().get("error"));
        frame("meta","C",1,Map.of("traceId","c","resumed",true)); frame("delta","C",2,Map.of("text","c"));
        frame("done","C",3,Map.of("finishReason","stop")); assertEquals("c",service.finish(7L,SID,RUN).get("answer"));
    }
    @Test void simultaneousTupleIsRecordedOnce() throws Exception {
        begin(); frame("meta","A",1,Map.of());
        ExecutorService pool=Executors.newFixedThreadPool(8); CountDownLatch start=new CountDownLatch(1);
        try { List<Future<?>> jobs=new ArrayList<>(); for(int i=0;i<8;i++) jobs.add(pool.submit(()->{try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);} frame("delta","A",2,Map.of("text","x"));}));
            start.countDown(); for(Future<?> job:jobs) job.get(5,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        Map<String,Object> result=service.finish(7L,SID,RUN); assertEquals("x",result.get("answer")); assertEquals(2,((List<?>)result.get("trace")).size());
    }

    private void leased(String generation, String event, String attempt, int seq, Map<String,Object> fields) {
        Map<String,Object> data=new LinkedHashMap<>(fields);
        data.put("attemptId",attempt); data.put("sequence",seq);
        service.record(7L,SID,RUN,generation,event,data);
    }
    @Test void delayedFinallyCannotCloseOrDiscardTheNextHandler() throws Exception {
        String a=service.beginWriter(7L,SID,RUN,AgentRunServiceTest.request()); store.grant(service, RUN);
        leased(a,"meta","A",1,Map.of()); leased(a,"delta","A",2,Map.of("text","a"));
        CountDownLatch finallyEntered=new CountDownLatch(1), release=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<?> old=pool.submit(()->{finallyEntered.countDown(); try {assertTrue(release.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException ex){throw new RuntimeException(ex);} service.finish(7L,SID,RUN,a);});
            assertTrue(finallyEntered.await(5,TimeUnit.SECONDS));
            String b=service.resumeWriter(7L,SID,RUN,"b"); assertNotEquals(a,b);
            leased(b,"meta","B",1,Map.of("traceId","b","resumed",true));
            leased(b,"delta","B",2,Map.of("text","b"));
            release.countDown(); old.get(5,TimeUnit.SECONDS);
            assertEquals("running",live().get("status")); assertNull(live().get("finishedAt"));
            assertEquals("b",live().get("answer")); // unsaved pending text must survive
            leased(a,"error","A",99,Map.of("scope","run"));
            leased(b,"delta","B",3,Map.of("text","!"));
            leased(b,"done","B",4,Map.of("finishReason","stop"));
            Map<String,Object> result=service.finish(7L,SID,RUN,b);
            assertEquals("done",result.get("status")); assertEquals("b!",result.get("answer"));
            assertNull(result.get("error")); assertNotNull(result.get("finishedAt"));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void resumeClearsOldFinishAndSealedHandoverHasNoWriter() {
        String a=service.beginWriter(7L,SID,RUN,AgentRunServiceTest.request()); store.grant(service, RUN);
        service.finish(7L,SID,RUN,a);
        String b=service.resumeWriter(7L,SID,RUN,"b"); assertNotNull(b); assertNull(live().get("finishedAt"));
        // A sealed completed handover must remain read-only, including elapsed.
        leased(b,"done","B",1,Map.of("finishReason","stop","at",30));
        Map<String,Object> sealed=service.finish(7L,SID,RUN,b);
        assertNull(service.resumeWriter(7L,SID,RUN,"handover"));
        leased(null,"meta","H",1,Map.of("deliveryMode","handover","at",999));
        leased(null,"done","H",2,Map.of("finishReason","stop","at",999));
        assertEquals(sealed,live());
    }

    @Test void recordResumeAndOldFinishSerializeWithoutLostUpdates() throws Exception {
        String a=service.beginWriter(7L,SID,RUN,AgentRunServiceTest.request()); store.grant(service, RUN);
        leased(a,"meta","A",1,Map.of()); leased(a,"delta","A",2,Map.of("text","buffered"));
        CountDownLatch saving=new CountDownLatch(1), release=new CountDownLatch(1), contenders=new CountDownLatch(2);
        org.mockito.Mockito.doAnswer(inv -> {
            com.nexus.chat.model.agent.AgentRunRecord row=inv.getArgument(0);
            if (row.getPayloadJson().contains("blocking-stage")) {
                saving.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            }
            store.savedRuns.put(row.getRunId(),store.mapper.convertValue(row,com.nexus.chat.model.agent.AgentRunRecord.class));
            store.runSaves.incrementAndGet(); return row;
        }).when(store.runs).saveAndFlush(org.mockito.ArgumentMatchers.any(com.nexus.chat.model.agent.AgentRunRecord.class));
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {
            Future<?> recording=pool.submit(()->leased(a,"stage","A",3,Map.of("stage","blocking-stage")));
            assertTrue(saving.await(5,TimeUnit.SECONDS));
            Future<String> resuming=pool.submit(()->{contenders.countDown();return service.resumeWriter(7L,SID,RUN,"b");});
            Future<?> finishing=pool.submit(()->{contenders.countDown();service.finish(7L,SID,RUN,a);});
            assertTrue(contenders.await(5,TimeUnit.SECONDS));
            assertFalse(resuming.isDone()); assertFalse(finishing.isDone());
            release.countDown(); recording.get(5,TimeUnit.SECONDS);
            String b=resuming.get(5,TimeUnit.SECONDS); finishing.get(5,TimeUnit.SECONDS);
            assertEquals("running",live().get("status")); assertNull(live().get("finishedAt"));
            assertEquals("buffered",live().get("answer")); assertEquals(3,((List<?>)live().get("trace")).size());
            leased(b,"meta","B",1,Map.of("traceId","b","resumed",true));
            leased(b,"delta","B",2,Map.of("text","new")); leased(b,"done","B",3,Map.of("finishReason","stop"));
            assertEquals("new",service.finish(7L,SID,RUN,b).get("answer"));
            assertEquals(6,((List<?>)live().get("trace")).size());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void newlyLeasedEarlyErrorAndDoneRemainOneAttempt() {
        begin(); frame("meta","A",1,Map.of()); service.finish(7L,SID,RUN);
        String b=service.resumeWriter(7L,SID,RUN,"b");
        leased(b,"error","B",1,Map.of("scope","run","code","UPSTREAM","at",10));
        leased(b,"done","B",2,Map.of("finishReason","error","modelCalls",3,"at",20));
        Map<String,Object> result=service.finish(7L,SID,RUN,b);
        assertEquals("B",result.get("attemptId")); assertEquals("error",result.get("status"));
        assertEquals(3,result.get("modelCalls")); assertEquals(20L,result.get("elapsedMs"));
    }
}
