package com.mineagent.engine.llm;

import java.util.List;

/** AI21 Studio Jamba native endpoint (OpenAI-shaped JSON, AI21-specific route). */
public final class AI21Provider extends OpenAICompatibleProvider {
    @Override public AI21Provider newSession() { return new AI21Provider(); }
    public AI21Provider() {
        super("ai21", "AI21 Jamba", "https://api.ai21.com/studio/v1",
                List.of("jamba-1.5-large", "jamba-1.5-mini", "jamba-1.6-mini"),
                java.util.Set.of("jamba-"));
    }

    @Override public boolean supportsModel(String model) {
        return model != null && (model.startsWith("jamba-") || model.startsWith("AI21/"));
    }
}
