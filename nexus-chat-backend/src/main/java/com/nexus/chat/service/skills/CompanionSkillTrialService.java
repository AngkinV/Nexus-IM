package com.nexus.chat.service.skills;

import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.service.*;
import com.nexus.chat.service.agent.AgentRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Preview uses the same persona builder, never stores turns/growth/memory or executes skill tools. */
@Service
@RequiredArgsConstructor
public class CompanionSkillTrialService {
    private final CompanionSkillService skills;
    private final CompanionSkillRuntime runtime;
    private final CompanionRoleRepository roles;
    private final CompanionGrowthRepository growth;
    private final CompanionModelResolver resolver;
    private final CompanionModelService models;
    private final AgentRunService runs;

    public Map<String,Object> trial(Long owner,Long skill,int revision,Long roleId,String input,boolean replaceIdentity) {
        if (input==null || input.isBlank() || input.length()>2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Trial input exceeds bounds");
        CompanionRole role=roles.findByIdAndUserId(roleId,owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Role not found"));
        var cfg=resolver.resolve(owner,roleId);
        if (!cfg.usable()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Role has no usable model channel");
        var frame=runtime.trial(owner,role,skill,revision,skills.export(owner,skill,revision).toString(),input,replaceIdentity);
        var bond=growth.findByUserIdAndRoleId(owner,roleId).orElse(null);
        var context=frame.decorate(new CompanionPromptBuilder.Context(role,null,cfg.binding().getPersonaLock(),
                bond==null?null:bond.getIntimacy(),bond==null?null:bond.getTrust(),List.of(),false));
        CompanionMessage source=new CompanionMessage(); source.setSenderType(CompanionMessage.SenderType.user); source.setContent(input);
        var result=models.probe(context,List.of(source),cfg.binding(),cfg.credential());
        Map<String,Object> response=new LinkedHashMap<>(); response.put("ok",result.success());
        response.put("reply",result.success()?result.content():""); response.put("error",result.error());
        response.put("contextReceipt",Map.of("trial",true,"skills",frame.skills(),"memoryStatus","isolated","modelRequestAttempted",true));
        return response;
    }
    public CompanionSkillService.SkillView fromArtifact(Long owner,String sessionId,String artifactId) {
        if (sessionId==null || artifactId==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Artifact source required");
        var artifact=runs.findArtifact(owner,sessionId,artifactId);
        Object content=artifact.get("content");
        if (!(content instanceof String raw)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Artifact does not contain a skill package");
        // Ownership/scope comes from the persisted artifact. Client-supplied prose is not used.
        String bundle=raw.stripLeading().startsWith("---") ? CompanionSkillRuntime.encode(Map.of("files",Map.of("SKILL.md",raw))) : raw;
        return skills.createFromArtifact(owner,bundle,artifactId);
    }
}
