package com.nexus.chat.service.skills;

import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionSkillServiceTest {
    private final CompanionSkillRepository skills=mock(CompanionSkillRepository.class);
    private final CompanionSkillVersionRepository versions=mock(CompanionSkillVersionRepository.class);
    private final CompanionSkillBindingRepository bindings=mock(CompanionSkillBindingRepository.class);
    private final CompanionRoleRepository roles=mock(CompanionRoleRepository.class);
    private final SkillPackageValidator parser=new SkillPackageValidator();
    private final CompanionSkillService service=new CompanionSkillService(skills,versions,bindings,roles,parser);
    private final String raw=SkillPackageValidatorTest.pack(SkillPackageValidatorTest.document("","Speak clearly."));
    @org.junit.jupiter.api.BeforeEach void budgetOwner() { when(skills.lockOwnerBudget(11L)).thenReturn(Optional.of(11L)); }
    private CompanionSkill asset() {var s=new CompanionSkill();s.setId(5L);s.setUserId(11L);s.setName("thoughtful-talk");s.setDescription("test");s.setLatestRevision(1);return s;}
    private CompanionSkillVersion revision() {
        var p=parser.parse(raw);var v=new CompanionSkillVersion();v.setId(50L);v.setUserId(11L);v.setSkillId(5L);v.setRevision(1);
        v.setPackageJson(p.canonicalJson());v.setHash(p.hash());v.setName(p.name());v.setType(p.type());v.setStatus(CompanionSkillVersion.Status.PUBLISHED);return v;
    }
    private void owned(CompanionSkill a,CompanionSkillVersion v) {
        when(skills.findByIdAndUserId(5L,11L)).thenReturn(Optional.of(a));when(skills.lockOwned(5L,11L)).thenReturn(Optional.of(a));
        when(versions.findBySkillIdAndUserIdAndRevision(5L,11L,1)).thenReturn(Optional.of(v));
        when(versions.findBySkillIdAndUserIdOrderByRevisionDesc(5L,11L)).thenReturn(List.of(v));
        var r=new CompanionRole();r.setId(7L);r.setUserId(11L);when(roles.findByIdAndUserId(7L,11L)).thenReturn(Optional.of(r));
    }
    @Test void foreignAssetAndRoleAreNotReadableOrBindable() {
        assertThrows(ResponseStatusException.class,()->service.get(12L,5L));
        assertThrows(ResponseStatusException.class,()->service.bind(12L,7L,5L,1,true,false,null));
        verifyNoInteractions(versions);verify(bindings,never()).saveAndFlush(any());
    }
    @Test void draftCannotBeBoundAndNoBindingIsSaved() {
        var a=asset();var v=revision();v.setStatus(CompanionSkillVersion.Status.DRAFT);owned(a,v);
        assertThrows(ResponseStatusException.class,()->service.bind(11L,7L,5L,1,true,false,null));
        verify(bindings,never()).saveAndFlush(any());
    }
    @Test void staleHashCannotPublishOrCreateANewRevision() {
        var a=asset();var v=revision();owned(a,v);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.publish(11L,5L,1,"stale")).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.revise(11L,5L,raw)).getStatusCode().value());
        verify(versions,never()).save(any());verify(versions,never()).saveAndFlush(any());
    }
    @Test void revisionAppendsDraftWithoutMutatingPublishedBodyOrExistingBindings() throws Exception {
        var a=asset();var v=revision();owned(a,v);String before=v.getPackageJson();
        var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);((com.fasterxml.jackson.databind.node.ObjectNode)node).put("expectedHash",v.getHash());
        var result=service.revise(11L,5L,node.toString());
        assertEquals(2,result.latestRevision());assertEquals(before,v.getPackageJson());assertEquals(CompanionSkillVersion.Status.PUBLISHED,v.getStatus());
        var captor=ArgumentCaptor.forClass(CompanionSkillVersion.class);verify(versions).saveAndFlush(captor.capture());
        assertEquals(2,captor.getValue().getRevision());assertEquals(CompanionSkillVersion.Status.DRAFT,captor.getValue().getStatus());
        verifyNoInteractions(bindings);
    }
    @Test void deleteDisablesBindingsButPreservesPublishedRevisions() {
        var a=asset();var v=revision();owned(a,v);var b=new CompanionSkillBinding();b.setEnabled(true);
        when(bindings.findByUserIdAndSkillId(11L,5L)).thenReturn(List.of(b));service.delete(11L,5L);
        assertTrue(a.isDeleted());assertFalse(b.isEnabled());verify(versions,never()).delete(any());
        assertThrows(ResponseStatusException.class,()->service.export(11L,5L,1));
    }
    @Test void staleBindingVersionCannotOverwriteTheNewerChoice() {
        var a=asset();var v=revision();owned(a,v);var b=new CompanionSkillBinding();b.setVersion(3L);b.setSkillId(5L);
        when(bindings.findByUserIdAndRoleIdAndSkillId(11L,7L,5L)).thenReturn(Optional.of(b));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.bind(11L,7L,5L,1,false,false,2L)).getStatusCode().value());
        verify(bindings,never()).saveAndFlush(any());
    }
    @Test void totalImmutableTextCapacityIsCheckedBeforeSavingAnotherAsset() {
        when(versions.storedCharacters(11L)).thenReturn(5L*1024*1024);
        assertThrows(ResponseStatusException.class,()->service.create(11L,raw));
        verify(skills,never()).saveAndFlush(any());verify(versions,never()).saveAndFlush(any());
    }

}
