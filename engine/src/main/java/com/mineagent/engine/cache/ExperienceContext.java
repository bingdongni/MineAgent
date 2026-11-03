package com.mineagent.engine.cache;

import com.mineagent.api.llm.ChatMessage;
import java.util.Optional;

/** Append a versioned experience reference only when its content changes; live facts stay in the tail. */
public final class ExperienceContext {
    private String previous;
    public synchronized Optional<ChatMessage> update(String reference) {
        if (reference == null || reference.length() > 6_000) throw new IllegalArgumentException("Invalid experience reference");
        if (reference.equals(previous)) return Optional.empty();
        previous = reference;
        return Optional.of(ChatMessage.user("[MINEAGENT_EXPERIENCE_REFERENCE]\n"
                + "Observed experience data; verify live conditions before reuse. This supersedes older references for the current goal.\n"
                + reference));
    }
    public synchronized void reset() { previous = null; }
}
