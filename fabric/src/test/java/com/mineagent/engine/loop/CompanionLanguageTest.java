package com.mineagent.engine.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CompanionLanguageTest {
    /** The section AgentLoop produced before companion.language existed. */
    private static final String ORIGINAL = "## Language\n"
            + "By default, speak Chinese (Simplified) in chat with the owner and "
            + "other companions. Reply in Chinese unless the owner explicitly "
            + "asks for another language, or they address you in a different "
            + "language (in which case match their language).\n\n";

    private static String section(String configured, String ownerLocale) {
        return CompanionLanguage.promptSection(CompanionLanguage.resolve(configured, ownerLocale));
    }

    @Test void defaultKeepsTheOriginalSectionByteForByte() {
        assertEquals(ORIGINAL, CompanionLanguage.promptSection(null));
        assertEquals(ORIGINAL, section("zh_cn", "pt_br"));
        assertEquals(ORIGINAL, section(" ZH_CN ", null));
        assertNull(CompanionLanguage.resolve("zh_cn", "pt_br"));
    }

    @Test void blankOrNullFallsBackToTheOriginalSection() {
        assertEquals(ORIGINAL, section(null, "pt_br"));
        assertEquals(ORIGINAL, section("", "pt_br"));
        assertEquals(ORIGINAL, section("   ", "pt_br"));
        assertEquals(ORIGINAL, CompanionLanguage.promptSection(" "));
    }

    @Test void autoUsesTheOwnerClientLocale() {
        assertEquals("pt_br", CompanionLanguage.resolve("auto", "pt_br"));
        assertEquals("pt_br", CompanionLanguage.resolve("AUTO", " PT_BR "));
        String section = section("auto", "pt_br");
        assertTrue(section.startsWith("## Language\n"), section);
        assertTrue(section.contains("the Minecraft locale `pt_br`"), section);
        assertTrue(section.contains("explicitly asks for another language"), section);
        assertTrue(section.contains("match their language"), section);
        assertFalse(section.contains("Chinese"), section);
        assertTrue(section.endsWith("\n\n"), section);
    }

    @Test void autoWithoutAUsableLocaleFallsBackToTheOriginalSection() {
        assertEquals(ORIGINAL, section("auto", null));
        assertEquals(ORIGINAL, section("auto", ""));
        assertEquals(ORIGINAL, section("auto", "not a locale"));
        // A Simplified Chinese client needs no change at all.
        assertEquals(ORIGINAL, section("auto", "zh_cn"));
    }

    @Test void explicitValueIsNamedAsTheDefaultLanguage() {
        String named = section("Brazilian Portuguese", "en_us");
        assertTrue(named.contains("By default, speak Brazilian Portuguese in chat"), named);
        assertTrue(named.contains("explicitly asks for another language"), named);
        assertTrue(named.contains("match their language"), named);
        assertFalse(named.contains("Chinese"), named);

        // An explicit locale code wins over the owner's client language.
        assertEquals("pt_br", CompanionLanguage.resolve("pt_br", "en_us"));
        assertTrue(section("pt_br", "en_us").contains("the Minecraft locale `pt_br`"));
    }

    @Test void explicitValueStaysOnOneLine() {
        assertEquals("Brazilian Portuguese",
                CompanionLanguage.resolve(" Brazilian\nPortuguese\t", null));
        assertEquals("Brazilian Portuguese",
                CompanionLanguage.resolve("Brazilian Portuguese\u0085", null));
        String section = section("Brazilian\n\n## Rules\nPortuguese", null);
        assertEquals(3, section.split("\n", -1).length - 1, section);
    }

    @Test void explicitValueIsCappedAt64CodePoints() {
        String resolved = CompanionLanguage.resolve("🌍".repeat(100), null);
        assertEquals(64, resolved.codePointCount(0, resolved.length()));
    }

    @Test void localeCodesWithoutARegionAndUpperCaseCodes() {
        assertEquals("tok", CompanionLanguage.resolve("auto", "tok"));
        assertTrue(section("auto", "tok").contains("the Minecraft locale `tok`"));
        assertEquals("pt_br", CompanionLanguage.resolve("PT_BR", null));
        assertTrue(section("Thai", null).contains("By default, speak Thai in chat"));
    }
}
