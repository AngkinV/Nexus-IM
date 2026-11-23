package com.nexus.chat.service.agent.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.agent.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.repository.agent.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static com.nexus.chat.service.agent.scope.ScopeDtos.*;

@Service
@RequiredArgsConstructor
public class AgentScopeService {
    private final AgentScopeBindingRepository bindings;
    private final AgentScopeGrantRepository grants;
    private final ChatMemberRepository members;
    private final ChatRepository chats;
    private final ContactRepository contacts;
    private final ObjectMapper json;
    private static final ThreadLocal<AuthorizedScope> RUNTIME = new ThreadLocal<>();
    public static AutoCloseable runtime(String header) {
        if (RUNTIME.get() != null) throw denied();
        try {
            if (header == null || !header.matches("[A-Za-z0-9_-]+") || header.length()>32768) throw denied();
            RUNTIME.set(new ObjectMapper().findAndRegisterModules().readValue(Base64.getUrlDecoder().decode(header),AuthorizedScope.class));
            return RUNTIME::remove;
        } catch(Exception e) { RUNTIME.remove(); throw denied(); }
    }
    private static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Scope denied"); }
    private <T> T decode(String s,Class<T> t) { try { T value=json.readValue(s,t); if(value==null) throw denied(); return value; } catch(Exception e) { throw denied(); } }
    private String encode(Object v) { try { return json.writeValueAsString(v); } catch(Exception e) { throw new IllegalStateException(e); } }
    private AgentScopeBinding owned(Long actor,String session) {
        var b=bindings.lockBySessionId(session).orElseThrow(AgentScopeService::denied);
        if (!Objects.equals(actor,b.actorUserId)||b.tombstone) throw denied(); return b;
    }
    @Transactional public Binding get(Long actor,String session) { var b=owned(actor,session); return new Binding(b.bindingVersion,b.sessionGeneration,decode(b.selectionJson,Selection.class)); }
    @Transactional public Binding create(Long actor,String session,Selection selection) {
        if(actor==null||actor<1||session==null||!session.matches("[A-Za-z0-9_-]{1,64}")||bindings.existsById(session)) throw denied();
        resolve(actor,selection); var b=new AgentScopeBinding(); b.sessionId=session; b.actorUserId=actor; b.bindingVersion=1; b.selectionJson=encode(selection); bindings.saveAndFlush(b);
        return new Binding(b.bindingVersion,b.sessionGeneration,selection);
    }
    /** Server-only compatibility authority: no client target type, exactly one real member chat. */
    @Transactional public AuthorizedScope authorizeOneShotChat(Long actor,String session,String run,Long chatId) {
        if(actor==null||actor<1||chatId==null||!members.existsByChatIdAndUserId(chatId,actor)||chats.findById(chatId).isEmpty()) throw denied();
        if(session==null||!session.matches("as_oneshot_[A-Za-z0-9_-]+")||bindings.existsById(session)) throw denied();
        var b=new AgentScopeBinding(); b.sessionId=session; b.actorUserId=actor; b.bindingVersion=1;
        b.selectionJson=encode(new Selection(List.of(),TimeRange.unbounded())); bindings.saveAndFlush(b);
        var ids=List.of(chatId); var users=List.<Long>of(); var range=TimeRange.unbounded();
        var s=new AuthorizedScope(1,UUID.randomUUID().toString(),actor,session,run,1,1,b.sessionGeneration,ids,users,range,key(actor,session,b.sessionGeneration,ids,users,range));
        var g=new AgentScopeGrant(); g.grantId=s.grantId(); g.sessionId=session; g.runId=run; g.actorUserId=actor; g.active=true; g.scopeJson=encode(s); grants.saveAndFlush(g); return s;
    }
    @Transactional public Binding update(Long actor,String session,Update update) {
        if(update==null||update.selection()==null) throw denied();
        var b=owned(actor,session); checkVersion(b,update.expectedBindingVersion()); var selection=checkedSelection(actor,update.selection());
        b.bindingVersion++; b.selectionJson=encode(selection); bindings.saveAndFlush(b); revoke(session);
        return new Binding(b.bindingVersion,b.sessionGeneration,selection);
    }
    private void checkVersion(AgentScopeBinding b,Long expected) { if(expected==null||expected!=b.bindingVersion) throw new ResponseStatusException(HttpStatus.CONFLICT,"Binding version conflict"); }
    public Selection checkedSelection(Long actor, Selection selection) {
        resolve(actor,selection);
        return new Selection(selection.targets().stream().distinct()
                .sorted(Comparator.comparing(Target::type).thenComparing(Target::id)).toList(),selection.timeRange());
    }
    private List<Long> resolve(Long actor,Selection s) {
        if(s==null||s.targets()==null||s.timeRange()==null) throw denied(); var ids=new TreeSet<Long>();
        for(var t:s.targets()) {
            if(t.id()==null||t.id()<1) throw denied();
            if("group".equals(t.type())) {
                if(!members.existsByChatIdAndUserId(t.id(),actor)||chats.findById(t.id()).filter(c->c.getType()==com.nexus.chat.model.Chat.ChatType.group).isEmpty()) throw denied(); ids.add(t.id());
            } else if("contact".equals(t.type())) {
                if(!contacts.existsByUserIdAndContactUserId(actor,t.id())) throw denied();
                chats.findDirectChatBetweenUsers(actor,t.id()).ifPresent(c->ids.add(c.getId()));
            } else throw denied();
        } return List.copyOf(ids);
    }
    @Transactional public AuthorizedScope authorize(Long actor,String session,String run,Selection override,boolean confirmed,Long expected,String retryOf,boolean resume) {
        if(actor==null||actor<1||session==null||!session.matches("[A-Za-z0-9_-]{1,64}")||run==null||!run.matches("[A-Za-z0-9_-]{1,64}")) throw denied();
        var b=owned(actor,session); checkVersion(b,expected); if(!confirmed) throw denied();
        var selection=resume && override==null ? new Selection(List.of(),TimeRange.unbounded()) : override==null?decode(b.selectionJson,Selection.class):override;
        var ids=resolve(actor,selection); var users=selection.targets().stream().filter(t->"contact".equals(t.type())).map(Target::id).distinct().sorted().toList();
        var key=key(actor,session,b.sessionGeneration,ids,users,selection.timeRange());
        var previous=grants.findBySessionIdAndRunId(session,resume?run:retryOf==null?run:retryOf);
        long version=1;
        if(resume||retryOf!=null) {
            var old=previous.stream().map(g->decode(g.scopeJson,AuthorizedScope.class)).max(Comparator.comparingLong(AuthorizedScope::authorizationVersion)).orElseThrow(AgentScopeService::denied);
            // Durable Java receipt is the maximum, not a live grant prerequisite.
            String sourceRun=resume?run:retryOf;
            if(!Objects.equals(old.actorUserId(),actor)||!Objects.equals(old.sessionId(),session)
                    ||!Objects.equals(old.runId(),sourceRun)||old.schemaVersion()!=1
                    ||old.sessionGeneration()!=b.sessionGeneration
                    ||!key(actor,session,old.sessionGeneration(),old.chatIds(),old.contactUserIds(),old.timeRange()).equals(old.scopeKey())) throw denied();
            for(var persisted:previous) {
                if(!Objects.equals(persisted.actorUserId,actor)||!Objects.equals(persisted.sessionId,session)
                        ||!Objects.equals(persisted.runId,sourceRun)) throw denied();
            }
            if (resume && override == null) {
                ids=old.chatIds(); users=old.contactUserIds(); selection=new Selection(List.of(),old.timeRange());
                key=key(actor,session,b.sessionGeneration,ids,users,selection.timeRange());
            }
            if(resume&&!key.equals(old.scopeKey())) throw denied();
            if(!old.chatIds().containsAll(ids)||!old.contactUserIds().containsAll(users)||!within(selection.timeRange(),old.timeRange())) throw denied();
            if(resume) version=old.authorizationVersion()+1;
            // A retry is a new run: it never re-grants an existing run's checkpoint.
            if(!resume&&(run.equals(retryOf)||!grants.findBySessionIdAndRunId(session,run).isEmpty())) throw denied();
        } else if(!previous.isEmpty()) throw denied();
        // Recheck only the newly selected/narrowed scope, never revoked members outside it.
        for(Long id:ids) if(!members.existsByChatIdAndUserId(id,actor)||chats.findById(id).isEmpty()) throw denied();
        for(Long id:users) if(!contacts.existsByUserIdAndContactUserId(actor,id)) throw denied();
        for(var g:grants.findBySessionIdAndRunId(session,run)) {g.active=false; grants.save(g);}
        var scope=new AuthorizedScope(1,UUID.randomUUID().toString(),actor,session,run,b.bindingVersion,version,b.sessionGeneration,ids,users,selection.timeRange(),key);
        var g=new AgentScopeGrant(); g.grantId=scope.grantId(); g.sessionId=session; g.runId=run; g.actorUserId=actor; g.active=true; g.scopeJson=encode(scope); grants.saveAndFlush(g); return scope;
    }
    private static boolean within(TimeRange child,TimeRange parent) {
        return (parent.fromInclusive()==null||(child.fromInclusive()!=null&&!child.fromInclusive().isBefore(parent.fromInclusive()))) && (parent.toExclusive()==null||(child.toExclusive()!=null&&!child.toExclusive().isAfter(parent.toExclusive())));
    }
    @Transactional public AuthorizedScope validate(Long actor,String session,String run,AuthorizedScope s) {
        if(s==null||s.schemaVersion()!=1||!Objects.equals(actor,s.actorUserId())||!Objects.equals(session,s.sessionId())||!Objects.equals(run,s.runId())) throw denied();
        var b=owned(actor,session); var g=grants.findById(s.grantId()).orElseThrow(AgentScopeService::denied); var p=decode(g.scopeJson,AuthorizedScope.class);
        if(!g.active||!Objects.equals(g.actorUserId,actor)||!Objects.equals(g.sessionId,session)||!Objects.equals(g.runId,run)||s.sessionGeneration()!=b.sessionGeneration||s.bindingVersion()!=b.bindingVersion||s.authorizationVersion()!=p.authorizationVersion()||s.sessionGeneration()!=p.sessionGeneration()||!p.chatIds().containsAll(s.chatIds())||!p.contactUserIds().containsAll(s.contactUserIds())||!within(s.timeRange(),p.timeRange())||!key(actor,session,b.sessionGeneration,s.chatIds(),s.contactUserIds(),s.timeRange()).equals(s.scopeKey())) throw denied();
        for(Long id:s.chatIds()) if(!members.existsByChatIdAndUserId(id,actor)) throw denied();
        for(Long id:s.contactUserIds()) if(!contacts.existsByUserIdAndContactUserId(actor,id)) throw denied();
        return s;
    }
    @Transactional public AuthorizedScope requireChat(Long actor,Long chat) { var s=RUNTIME.get(); if(s==null) throw denied(); validate(actor,s.sessionId(),s.runId(),s); if(!s.chatIds().contains(chat)) throw denied(); return s; }
    @Transactional public void requireUser(Long actor,Long user) {var s=RUNTIME.get(); if(s==null) throw denied(); validate(actor,s.sessionId(),s.runId(),s); if(!Objects.equals(actor,user)&&!s.contactUserIds().contains(user)) throw denied();}
    @Transactional public void invalidate(Long actor,String session,boolean deleted) {
        var b=bindings.lockBySessionId(session).orElseThrow(AgentScopeService::denied);
        if(!Objects.equals(actor,b.actorUserId)||(!deleted&&b.tombstone)) throw denied();
        if(!b.tombstone) { b.sessionGeneration++; b.tombstone=deleted; bindings.saveAndFlush(b); }
        // The generation gate commits independently of best-effort grant/physical cleanup.
    }
    @Transactional public void revokeSession(Long actor,String session) {
        var b=bindings.lockBySessionId(session).orElseThrow(AgentScopeService::denied);
        if(!Objects.equals(actor,b.actorUserId)) throw denied();
        revoke(session);
    }
    private void revoke(String session) {for(var g:grants.findBySessionId(session)) {g.active=false; grants.save(g);} grants.flush();}
    public static String key(Long actor,String session,long generation,List<Long> chats,List<Long> users,TimeRange range) {
        if(session.contains("|")) throw denied();
        String raw="v1|"+actor+"|"+session+"|"+generation+"|"+csv(chats)+"|"+csv(users)+"|"+(range.fromInclusive()==null?"*":range.fromInclusive().toEpochMilli())+"|"+(range.toExclusive()==null?"*":range.toExclusive().toEpochMilli());
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String csv(List<Long> ids) {return ids.stream().distinct().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}
}
