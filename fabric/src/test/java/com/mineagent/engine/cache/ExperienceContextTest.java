package com.mineagent.engine.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExperienceContextTest {
    @Test void unchangedExperienceDoesNotRepublishAContextMessage() {
        var context=new ExperienceContext(); assertTrue(context.update("verified rules").isPresent());
        for(int i=0;i<100;i++) assertTrue(context.update("verified rules").isEmpty());
    }
    @Test void invalidationAppendsACurrentReferenceInsteadOfReusingOldAuthority() {
        var context=new ExperienceContext(); context.update("rule one verified");
        assertTrue(context.update("No currently verified relevant rules").orElseThrow().content().contains("supersedes"));
    }
    @Test void historyCompactionRequiresRepublishingTheReference() {
        var context=new ExperienceContext(); context.update("rule one"); context.reset();
        assertTrue(context.update("rule one").isPresent());
    }
    @Test void differentCompanionsHaveIndependentReferences() {
        assertTrue(new ExperienceContext().update("rule one").isPresent());
        assertTrue(new ExperienceContext().update("rule one").isPresent());
    }
    @Test void oversizedReferencesAreRejectedRatherThanSilentlyTruncatingRules() {
        assertThrows(IllegalArgumentException.class,()->new ExperienceContext().update("x".repeat(6001)));
    }
}
