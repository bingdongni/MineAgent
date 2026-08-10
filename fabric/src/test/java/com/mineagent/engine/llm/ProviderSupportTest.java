package com.mineagent.engine.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderSupportTest {

    @Test
    void buildsEndpointsFromHostVersionRootOrCompletePath() {
        assertEquals("https://api.example.test/v1/chat/completions",
                ProviderSupport.endpoint("https://api.example.test",
                        "/v1/chat/completions"));
        assertEquals("https://api.example.test/v1/chat/completions",
                ProviderSupport.endpoint("https://api.example.test/v1",
                        "/v1/chat/completions"));
        assertEquals("https://api.example.test/v1/chat/completions",
                ProviderSupport.endpoint(
                        "https://api.example.test/v1/chat/completions",
                        "/v1/chat/completions"));
    }
}
