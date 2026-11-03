package com.mineagent.engine.loop;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Default chat language of a companion, from the {@code companion.language}
 * setting.
 *
 * <p>This class has no Minecraft types so the prompt text can be unit tested.
 * The built-in default reproduces the original Simplified Chinese section
 * byte for byte, so existing configurations keep the same cacheable system
 * prompt.
 */
public final class CompanionLanguage {

    /** Setting value for the original default, Simplified Chinese. */
    public static final String DEFAULT = "zh_cn";

    /** Setting value that follows the owner's Minecraft client language. */
    public static final String AUTO = "auto";

    /**
     * Minecraft locale codes, e.g. {@code pt_br}, {@code zlm_arab}, or codes
     * without a region such as {@code tok}. Matched in lower case only.
     */
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,4}(?:_[a-z0-9]{2,4})?");

    /** Whitespace, Unicode separators and control characters, collapsed to one space. */
    private static final Pattern BREAKS = Pattern.compile("[\\s\\p{Z}\\p{Cc}]+");

    /** Longest configured value kept, in code points. */
    private static final int MAX_LENGTH = 64;

    private CompanionLanguage() {}

    /**
     * Resolve the setting for one owner.
     *
     * @param configured  the {@code companion.language} value
     * @param ownerLocale the owner's Minecraft client locale, or null if unknown
     * @return the language to use, kept on one line, or null for the built-in default
     */
    public static String resolve(String configured, String ownerLocale) {
        String value = configured == null ? "" : BREAKS.matcher(configured).replaceAll(" ").strip();
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            value = value.substring(0, value.offsetByCodePoints(0, MAX_LENGTH)).strip();
        }
        if (value.equalsIgnoreCase(AUTO)) {
            // The locale comes from the client; use it only if it looks like one.
            String locale = ownerLocale == null ? "" : ownerLocale.strip().toLowerCase(Locale.ROOT);
            value = LOCALE.matcher(locale).matches() ? locale : "";
        } else if (value.indexOf('_') > 0
                && LOCALE.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
            value = value.toLowerCase(Locale.ROOT);
        }
        return value.isEmpty() || value.equalsIgnoreCase(DEFAULT) ? null : value;
    }

    /**
     * The "## Language" section of the system prompt.
     *
     * @param language a value returned by {@link #resolve}; null selects the
     *                 original Simplified Chinese section
     */
    public static String promptSection(String language) {
        if (language == null || language.isBlank() || language.equalsIgnoreCase(DEFAULT)) {
            return "## Language\n"
                    + "By default, speak Chinese (Simplified) in chat with the owner and "
                    + "other companions. Reply in Chinese unless the owner explicitly "
                    + "asks for another language, or they address you in a different "
                    + "language (in which case match their language).\n\n";
        }
        String target = LOCALE.matcher(language).matches()
                ? "the language of the Minecraft locale `" + language + "`"
                : language;
        return "## Language\n"
                + "By default, speak " + target + " in chat with the owner and "
                + "other companions. Reply in that language unless the owner explicitly "
                + "asks for another language, or they address you in a different "
                + "language (in which case match their language).\n\n";
    }
}
