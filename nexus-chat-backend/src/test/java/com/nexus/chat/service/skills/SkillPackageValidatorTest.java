package com.nexus.chat.service.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillPackageValidatorTest {
    private final SkillPackageValidator validator = new SkillPackageValidator();
    private static final ObjectMapper JSON = new ObjectMapper();
    static String document(String extra, String body) {
        return "---\nname: thoughtful-talk\ndescription: Listen and discuss carefully\n" + extra + "---\n" + body;
    }
    static String pack(String doc) { return pack(Map.of("SKILL.md",doc)); }
    static String pack(Map<String,String> files) {
        try { return JSON.writeValueAsString(Map.of("files",files)); } catch(Exception e) { throw new AssertionError(e); }
    }
    @Test void importsACommonSkillWithoutNexusMetadata() {
        var p=validator.parse(pack(document("", "Ask one specific question.\n")));
        assertEquals("thoughtful-talk",p.name()); assertEquals("interaction",p.type());
        assertEquals("Ask one specific question.",p.instructions()); assertEquals(64,p.hash().length());
    }
    @Test void personaPatchAndTriggerAreParsedRatherThanGuessed() {
        var p=validator.parse(pack(document("nexus:\n  type: personality\n  version: '2.1'\n  patch:\n    tone: concise\n  triggers: [work]\n", "Use plain language.")));
        assertEquals("personality",p.type()); assertEquals("2.1",p.packageVersion());
        assertEquals("concise",p.patch().get("tone")); assertEquals(List.of("work"),p.triggers());
    }
    @Test void canonicalHashIsStableForOrderingAndWindowsNewlines() {
        String doc=document("","Read references/a.md.");
        LinkedHashMap<String,String> a=new LinkedHashMap<>();a.put("SKILL.md",doc);a.put("references/a.md","notes\n");
        LinkedHashMap<String,String> b=new LinkedHashMap<>();b.put("references/a.md","notes\r\n");b.put("SKILL.md",doc.replace("\n","\r\n"));
        assertEquals(validator.parse(pack(a)).hash(),validator.parse(pack(b)).hash());
    }
    @ParameterizedTest @ValueSource(strings={"../bad.md","/bad.md","references/../../bad.md","references/a.sh","scripts/run.py","references/\\bad.md","references/%2e/x.md","references/.secret.md"})
    void dangerousPathsAndExecutableFilesAreRejected(String path) {
        assertThrows(ResponseStatusException.class,()->validator.parse(pack(Map.of("SKILL.md",document("","hello"),path,"text"))));
    }
    @Test void unsafeYamlTagsAliasesAndDuplicateKeysAreRejected() {
        for(String yaml:List.of("name: !!java/object 'test'\n","name: &n talk\ndescription: *n\n","name: a\nname: b\ndescription: text\n")) {
            assertThrows(ResponseStatusException.class,()->validator.parse(pack("---\n"+yaml+"---\nhello")));
        }
    }
    @Test void duplicateJsonKeysCannotSilentlyReplaceAnInstructionFile() throws Exception {
        String doc=JSON.writeValueAsString(document("","hello"));
        assertThrows(ResponseStatusException.class,()->validator.parse("{\"files\":{\"SKILL.md\":"+doc+",\"SKILL.md\":"+doc+"}}"));
    }
    @Test void unsupportedToolRequirementsAreNotSilentlyImported() {
        assertThrows(ResponseStatusException.class,()->validator.parse(pack(document("allowed-tools: [Bash]\n","hello"))));
        assertThrows(ResponseStatusException.class,()->validator.parse(pack(document("nexus:\n  requires: [network]\n","hello"))));
    }
    @Test void excessiveContentNonTextAndCredentialsAreRejected() {
        assertThrows(ResponseStatusException.class,()->validator.parse(pack(document("","x".repeat(SkillPackageValidator.MAX_FILE_BYTES)))));
        assertThrows(ResponseStatusException.class,()->validator.parse("{\"files\":{\"SKILL.md\":42}}"));
        assertThrows(ResponseStatusException.class,()->validator.parse(pack(document("","sk"+"-"+"fake".repeat(8)))));
    }
    @Test void invalidPersonaFieldsAndUnknownTypesAreRejected() {
        assertThrows(ResponseStatusException.class,()->validator.parse(pack(document("nexus:\n  patch:\n    apiKey: something\n","hello"))));
        assertThrows(ResponseStatusException.class,()->validator.parse(pack(document("nexus:\n  type: executable\n","hello"))));
    }
}
