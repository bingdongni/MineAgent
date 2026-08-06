package com.mineagent.engine.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SkillLibraryValidationTest {
    @Test void importRejectsMalformedQueryOnlyAndLegacyGenericSkills() {
        SkillLibrary library = new SkillLibrary();
        library.importAll(List.of(
                skill("broken", "not-json"),
                skill("query_only", "[{\"tool\":\"look_around\",\"args\":{}}]"),
                skill("general_task", "[{\"tool\":\"goto\",\"args\":{}}]"),
                skill("collect_logs", "[{\"tool\":\"goto\",\"args\":{}}]")));

        assertEquals(1, library.size());
        assertEquals("collect_logs", library.exportAll().getFirst().name());
    }

    @Test void registrationRequiresAtLeastOneRealAction() {
        SkillLibrary library = new SkillLibrary();
        library.registerSequence("status_loop", "status", "status",
                "[{\"tool\":\"get_self_status\",\"args\":{}}]", true);
        assertEquals(0, library.size());
    }

    private static SkillLibrary.Skill skill(String name, String sequence) {
        return new SkillLibrary.Skill(name, name, name, sequence,
                1.0, 1, 1L);
    }
}
