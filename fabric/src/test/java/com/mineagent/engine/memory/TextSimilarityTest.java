package com.mineagent.engine.memory;

import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.skill.SkillLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TextSimilarityTest {
    @Test void chineseCharacterNgramsOutrankUnrelatedText() {
        double related = TextSimilarity.score("收集木材建造房子", "砍树收集木材用于建造");
        double unrelated = TextSimilarity.score("收集木材建造房子", "寻找钻石并与僵尸战斗");
        assertTrue(related > unrelated);
    }

    @Test void learnedParameterizedSkillCanBeRetrievedInChinese() {
        SkillLibrary library = new SkillLibrary();
        library.registerSequence("wood_collection", "砍树并收集木材", "需要木材建造时",
                "[{\"tool\":\"auto_mine\",\"args\":{\"block_id\":\"minecraft:oak_log\"}}]",
                true);
        assertFalse(library.retrieve("请收集一些木材来建造房屋").isEmpty());
    }

    @Test void successfulRelevantExperienceIsNotHiddenByUnrelatedFailure() {
        ExperienceStore store = new ExperienceStore();
        store.record("1", "auto_mine", "收集木材", TaskState.SUCCESS,
                TaskSnapshot.running("complete", "logs collected"), "oak logs", 10L);
        store.record("2", "goto", "寻找村庄", TaskState.FAILED,
                TaskSnapshot.running("navigate", "failed"), "path timeout", 20L);
        String summary = store.summarizeForPrompt("收集木材建造");
        assertTrue(summary.contains("auto_mine"));
        assertFalse(summary.contains("path timeout"));
    }

    @Test void failedAdaptationDoesNotOverwriteVerifiedSkillTrace() {
        SkillLibrary library = new SkillLibrary();
        library.registerSequence("bridge", "safe bridge", "cross gap",
                "[{\"tool\":\"build\",\"args\":{\"block\":\"stone\"}}]", true);
        library.registerSequence("bridge", "unsafe retry", "cross gap",
                "[{\"tool\":\"build\",\"args\":{\"block\":\"sand\"}}]", false);
        assertTrue(library.getActionSequence("bridge").contains("stone"));
        assertFalse(library.getActionSequence("bridge").contains("sand"));
    }
}
