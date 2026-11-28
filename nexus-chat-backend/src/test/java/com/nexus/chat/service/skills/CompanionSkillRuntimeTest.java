package com.nexus.chat.service.skills;

import com.nexus.chat.model.*;
import com.nexus.chat.repository.TavernSessionRepository;
import com.nexus.chat.service.CompanionPromptBuilder;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionSkillRuntimeTest {
    private final CompanionSkillService skills=mock(CompanionSkillService.class);
    private final TavernSessionRepository sessions=mock(TavernSessionRepository.class);
    private final SkillPackageValidator parser=new SkillPackageValidator();
    private final CompanionSkillRuntime runtime=new CompanionSkillRuntime(skills,parser,sessions);
    private CompanionRole role() {
        var r=new CompanionRole();r.setId(7L);r.setUserId(11L);r.setName("original");r.setTone("original-tone");r.setTraits("[\"kind\"]");return r;
    }
    private TavernSession scene() {var s=new TavernSession();s.setId(100L);s.setUserId(11L);s.setRoleId(7L);return s;}
    private SkillPackageValidator.Parsed parsed(String extra,String body) {
        return parser.parse(SkillPackageValidatorTest.pack(SkillPackageValidatorTest.document(extra,body)));
    }
    @Test void changingABindingDoesNotChangeTheCurrentSceneButDoesChangeTheNext() {
        var r=role();var s=scene();
        var one=parsed("nexus:\n  type: personality\n  patch:\n    tone: one\n","Be concise.");
        var two=parsed("nexus:\n  type: personality\n  patch:\n    tone: two\n","Be expressive.");
        when(skills.runtimeBindings(11L,7L)).thenReturn(List.of(new CompanionSkillService.BoundSkill(5L,1,false,one)));
        runtime.freeze(s,r);
        when(skills.runtimeBindings(11L,7L)).thenReturn(List.of(new CompanionSkillService.BoundSkill(5L,2,false,two)));
        assertEquals("one",runtime.frame(11L,r,s,"hello").role().getTone());
        assertEquals(1,runtime.frame(11L,r,s,"hello").skills().get(0).revision());
        var next=scene();runtime.freeze(next,r);
        assertEquals("two",runtime.frame(11L,r,next,"hello").role().getTone());
        assertEquals("original-tone",r.getTone());
    }
    @Test void disablingSkillsDoesNotEraseAnAlreadyFrozenScene() {
        var r=role();var s=scene();var p=parsed("nexus:\n  type: personality\n","Speak warmly.");
        when(skills.runtimeBindings(11L,7L)).thenReturn(List.of(new CompanionSkillService.BoundSkill(5L,1,false,p)));
        runtime.freeze(s,r);when(skills.runtimeBindings(11L,7L)).thenReturn(List.of());
        assertEquals("loaded",runtime.frame(11L,r,s,"").skills().get(0).status());
        var next=scene();runtime.freeze(next,r);assertTrue(runtime.frame(11L,r,next,"").skills().isEmpty());
    }
    @Test void identityChangesRequireAnExplicitChoiceAndTrialWritesNothing() {
        var r=role();String raw=SkillPackageValidatorTest.pack(SkillPackageValidatorTest.document("nexus:\n  type: personality\n  patch:\n    name: replacement\n    tone: changed\n","Speak plainly."));
        assertEquals("original",runtime.trial(11L,r,5L,1,raw,"",false).role().getName());
        assertEquals("replacement",runtime.trial(11L,r,5L,1,raw,"",true).role().getName());
        assertEquals("original",r.getName());assertEquals("original-tone",r.getTone());
        verifyNoInteractions(sessions,skills);
    }
    @Test void receiptReportsOnlyFilesActuallyLoadedAndThePromptUsesEffectivePersona() {
        var r=role();String raw=SkillPackageValidatorTest.pack(Map.of("SKILL.md",SkillPackageValidatorTest.document("nexus:\n  type: personality\n  patch:\n    tone: new-tone\n","Read references/a.md."),"references/a.md","reference text","references/unused.md","not used"));
        var frame=runtime.trial(11L,r,5L,1,raw,"",false);
        assertEquals(List.of("SKILL.md","references/a.md"),frame.skills().get(0).files());
        String system=CompanionPromptBuilder.system(frame.decorate(new CompanionPromptBuilder.Context(r,null,95,null,null,List.of(),true)));
        assertTrue(system.contains("new-tone"));assertTrue(system.contains("reference text"));assertFalse(system.contains("not used"));
        assertTrue(system.indexOf("Read references")<system.indexOf("严格遵守上面的"));
    }
    @Test void nonTriggeredInteractionDoesNotPatchThePersona() {
        var r=role();String raw=SkillPackageValidatorTest.pack(SkillPackageValidatorTest.document("nexus:\n  triggers: [work]\n  patch:\n    tone: business\n","Discuss work."));
        var frame=runtime.trial(11L,r,5L,1,raw,"dinner",false);
        assertEquals("not_triggered",frame.skills().get(0).status());assertEquals("original-tone",frame.role().getTone());assertTrue(frame.prompt().isEmpty());
    }
    @Test void overBudgetSkillIsReportedAsOmittedNotApplied() {
        var r=role();String raw=SkillPackageValidatorTest.pack(SkillPackageValidatorTest.document("nexus:\n  patch:\n    tone: wrong\n","x".repeat(25000)));
        var frame=runtime.trial(11L,r,5L,1,raw,"",false);
        assertEquals("budget_exceeded",frame.skills().get(0).status());assertEquals("original-tone",frame.role().getTone());
    }
    @Test void aLegacySceneDoesNotLoadNewBindingsAndOwnerMustMatch() {
        var r=role();var s=scene();assertTrue(runtime.frame(11L,r,s,"hello").skills().isEmpty());
        verify(sessions).save(s);verifyNoInteractions(skills);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->runtime.frame(12L,r,s,"hello"));
    }
}
