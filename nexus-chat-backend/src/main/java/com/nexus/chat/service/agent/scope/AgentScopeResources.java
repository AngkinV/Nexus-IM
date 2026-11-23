package com.nexus.chat.service.agent.scope;

import com.nexus.chat.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AgentScopeResources {
    private final ContactRepository contacts;
    private final ChatRepository chats;
    private final ChatMemberRepository members;
    private final UserRepository users;
    public Map<String,Object> list(Long actor) {
        var people=contacts.findByUserId(actor).stream().map(c->{
            Map<String,Object> row=new LinkedHashMap<>(); row.put("type","contact");row.put("id",c.getContactUserId());
            row.put("label",users.findById(c.getContactUserId()).map(u->u.getUsername()).orElse(""));
            row.put("chatId",chats.findDirectChatBetweenUsers(actor,c.getContactUserId()).map(ch->ch.getId()).orElse(null));return row;
        }).toList();
        var groups=chats.findUserGroups(actor).stream().filter(c->members.existsByChatIdAndUserId(c.getId(),actor)).map(c->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("type","group");row.put("id",c.getId());row.put("chatId",c.getId());row.put("label",c.getName()==null?"":c.getName());return row;
        }).toList();
        return Map.of("contacts",people,"groups",groups);
    }
    public ScopeDtos.Selection selectionForChat(Long actor,Long chatId) {
        if(chatId == null) return new ScopeDtos.Selection(List.of(),ScopeDtos.TimeRange.unbounded());
        if(!members.existsByChatIdAndUserId(chatId,actor)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        var chat=chats.findById(chatId).orElseThrow();
        ScopeDtos.Target target;
        if(chat.getType()==com.nexus.chat.model.Chat.ChatType.group) target=new ScopeDtos.Target("group",chatId);
        else {
            var others=members.findByChatId(chatId).stream().map(m->m.getUserId()).filter(id->!actor.equals(id)).distinct().toList();
            if(others.size()!=1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Ambiguous direct chat");
            requireContact(actor,others.get(0)); target=new ScopeDtos.Target("contact",others.get(0));
        }
        return new ScopeDtos.Selection(List.of(target),ScopeDtos.TimeRange.unbounded());
    }
    public void requireContact(Long actor,Long user) {
        if(!actor.equals(user)&&!contacts.existsByUserIdAndContactUserId(actor,user)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Not an owned candidate");
    }
}
