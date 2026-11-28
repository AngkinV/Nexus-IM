package com.nexus.chat.service.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.service.*;
import com.nexus.chat.service.agent.AgentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionSkillTrialServiceTest {
    final CompanionSkillService skills=mock(CompanionSkillService.class);
    final SkillPackageValidator validator=new SkillPackageValidator();
    final CompanionRoleRepository roles=mock(CompanionRoleRepository.class);
    final CompanionGrowthRepository growth=mock(CompanionGrowthRepository.class);
    final CompanionModelResolver resolver=mock(CompanionModelResolver.class);
    final CompanionModelService models=mock(CompanionModelService.class);
    final AgentRunService runs=mock(AgentRunService.class);
    final CompanionSkillRuntime runtime=new CompanionSkillRuntime(skills,validator,mock(TavernSessionRepository.class));
    final CompanionSkillTrialService trial=new CompanionSkillTrialService(skills,runtime,roles,growth,resolver,models,runs);
    final String md=SkillPackageValidatorTest.document("nexus:\n  type: personality\n  patch:\n    tone: compact\n","Listen closely.");
    @Test void actualTrialAssemblerIsSharedAndDoesNotPersistOrPublish() throws Exception {
        var role=new CompanionRole();role.setId(7L);role.setUserId(11L);role.setName("role");role.setTone("base");
        when(roles.findByIdAndUserId(7L,11L)).thenReturn(Optional.of(role));
        var binding=new CompanionModelBinding();binding.setPersonaLock(95);
        when(resolver.resolve(11L,7L)).thenReturn(new CompanionModelResolver.Resolved(binding,new ModelCredential(),false,"model",null));
        when(skills.export(11L,5L,1)).thenReturn(new ObjectMapper().readTree(SkillPackageValidatorTest.pack(md)));
        when(models.probe(any(),anyList(),same(binding),any())).thenAnswer(i->{CompanionPromptBuilder.Context ctx=i.getArgument(0);assertEquals("compact",ctx.role().getTone());assertTrue(CompanionPromptBuilder.system(ctx).contains("Listen closely."));return CompanionModelService.ModelReply.success("trial reply");});
        var result=trial.trial(11L,5L,1,7L,"hello",false);assertEquals(true,result.get("ok"));assertEquals("trial reply",result.get("reply"));assertEquals(true,((Map<?,?>)result.get("contextReceipt")).get("trial"));
        assertEquals("base",role.getTone());verify(roles,never()).save(any());verify(growth,never()).save(any());verify(skills,never()).publish(anyLong(),anyLong(),anyInt(),anyString());verifyNoInteractions(runs);
    }
    @Test void artifactContentIsTakenFromOwnedPersistedSourceAndMarkdownIsWrapped() {
        when(runs.findArtifact(11L,"s","a")).thenReturn(Map.of("content",md));
        trial.fromArtifact(11L,"s","a");verify(skills).createFromArtifact(eq(11L),argThat(raw -> validator.parse(raw).instructions().equals("Listen closely.")),eq("a"));
        verify(runs).findArtifact(11L,"s","a");
    }
    @Test void deniedArtifactCannotBeSavedAsSkill() {
        when(runs.findArtifact(12L,"s","a")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class,()->trial.fromArtifact(12L,"s","a"));verifyNoInteractions(skills,models);
    }
    @Test void noConfiguredRoleModelDoesNotProduceFakeTrial() {
        var role=new CompanionRole();role.setId(7L);role.setUserId(11L);when(roles.findByIdAndUserId(7L,11L)).thenReturn(Optional.of(role));
        when(resolver.resolve(11L,7L)).thenReturn(new CompanionModelResolver.Resolved(null,null,false));
        assertEquals(422,assertThrows(ResponseStatusException.class,()->trial.trial(11L,5L,1,7L,"hello",false)).getStatusCode().value());verifyNoInteractions(models,skills);
    }
}
