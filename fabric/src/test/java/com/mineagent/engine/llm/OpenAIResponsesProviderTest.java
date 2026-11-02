package com.mineagent.engine.llm;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.ConversationItem;
import com.mineagent.api.llm.LLMCapabilities;
import com.mineagent.api.llm.LLMRequest;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.ProviderSessionState;
import com.mineagent.api.llm.provider.LLMProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OpenAIResponsesProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesMessagesCallsOpaqueItemsAndCachedUsage() {
        OpenAIResponsesProvider provider = new OpenAIResponsesProvider(new StubProvider());
        String raw = """
                {"id":"resp_1","model":"gpt-6-astra","status":"completed",
                 "output":[
                   {"type":"reasoning","id":"rs_1","encrypted_content":"abc"},
                   {"type":"message","content":[{"type":"output_text","text":"ready"}]},
                   {"type":"function_call","call_id":"call_1","name":"goto","arguments":"{\\"x\\":1}"},
                   {"type":"compaction","id":"cmp_1","encrypted_content":"xyz"}],
                 "usage":{"input_tokens":100,"output_tokens":7,"total_tokens":107,
                          "input_tokens_details":{"cached_tokens":80}}}
                """;

        LLMResponse response = provider.parseResponse(raw, "gpt-6-astra");

        assertEquals("resp_1", response.id());
        assertEquals("ready", response.choice().message().content());
        assertEquals(1, response.choice().message().toolCalls().size());
        assertEquals("call_1", response.choice().message().toolCalls().get(0).id());
        assertEquals(2, response.outputItems().size());
        assertTrue(response.outputItems().stream().allMatch(ConversationItem::opaque));
        assertEquals(80, response.usage().cachedPromptTokens());
        assertEquals(0.8, response.usage().promptCacheHitRate(), 1e-9);
        assertEquals("resp_1", provider.sessionState("gpt-6-astra").previousResponseId());
    }

    @Test
    void recordsCacheWritesSeparatelyFromReads() {
        var response = new OpenAIResponsesProvider(new StubProvider()).parseResponse("""
                {"id":"resp_cache","model":"custom","output":[],
                 "usage":{"input_tokens":15000,"output_tokens":20,
                  "input_tokens_details":{"cached_tokens":12000,"cache_write_tokens":3000}}}
                """, "custom");
        assertEquals(12000, response.usage().cachedPromptTokens());
        assertEquals(3000, response.usage().cacheCreationPromptTokens());
        assertEquals(15020, response.usage().totalTokens());
    }

    @Test
    void buildBodyDoesNotReplayOpaqueItemsOnStatefulContinuation() {
        OpenAIResponsesProvider provider = new OpenAIResponsesProvider(new StubProvider());
        List<ConversationItem> opaque = List.of(
                ConversationItem.opaque("reasoning", "{\"type\":\"reasoning\",\"id\":\"r\"}"));
        LLMRequest request = request("https://example.test", List.of(ChatMessage.toolResult(
                "call_1", "ok")), opaque, "resp_old");

        var body = provider.buildBody(request);
        assertEquals("resp_old", body.get("previous_response_id").getAsString());
        assertEquals(1, body.getAsJsonArray("input").size());
        assertEquals("function_call_output",
                body.getAsJsonArray("input").get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void fallsBackToChatWithCompleteHistoryOn404() throws IOException {
        AtomicReference<LLMRequest> captured = new AtomicReference<>();
        StubProvider fallback = new StubProvider() {
            @Override public LLMResponse complete(LLMRequest request) {
                captured.set(request);
                return response("fallback");
            }
        };
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        });
        server.start();

        OpenAIResponsesProvider provider = new OpenAIResponsesProvider(fallback);
        List<ChatMessage> full = List.of(ChatMessage.system("system"),
                ChatMessage.assistant("previous"), ChatMessage.user("continue"));
        LLMResponse result = provider.complete(request("http://127.0.0.1:" + server.getAddress().getPort(),
                full, List.of(), "resp_old"));

        assertEquals("fallback", result.choice().message().content());
        assertNotNull(captured.get());
        assertNull(captured.get().previousResponseId());
        assertEquals(full, captured.get().messages());
    }

    @Test
    void expiredPreviousResponseIsRebuiltOnce() throws IOException {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            int count = calls.updateAndGet(value -> value + 1);
            String body = count == 1
                    ? "{\"error\":{\"message\":\"previous_response_id expired\"}}"
                    : "{\"id\":\"resp_new\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"recovered\"}]}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(count == 1 ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        OpenAIResponsesProvider provider = new OpenAIResponsesProvider(new StubProvider());
        LLMResponse result = provider.complete(request("http://127.0.0.1:" + server.getAddress().getPort(),
                List.of(ChatMessage.system("system"), ChatMessage.user("full history")), List.of(), "resp_old"));

        assertEquals("recovered", result.choice().message().content());
        assertEquals(2, calls.get());
        assertEquals("resp_new", provider.sessionState("gpt-6-astra").previousResponseId());
    }

    private static LLMRequest request(String baseUrl, List<ChatMessage> messages,
                                      List<ConversationItem> items, String previous) {
        return new LLMRequest(baseUrl, "test-key", "gpt-6-astra", messages, messages,
                items, List.of(), 0.0, 128, "medium", previous, null,
                "test-cache", Duration.ofSeconds(3), true);
    }

    private static LLMResponse response(String text) {
        return new LLMResponse("id", "model",
                new LLMResponse.Choice(0, ChatMessage.assistant(text), "stop"),
                new LLMResponse.Usage(1, 1, 2), "stop");
    }

    private static class StubProvider implements LLMProvider {
        @Override public String providerId() { return "stub"; }
        @Override public String displayName() { return "stub"; }
        @Override public String defaultBaseUrl() { return "https://example.test"; }
        @Override public boolean supportsModel(String model) { return true; }
        @Override public List<String> defaultModels() { return List.of("model"); }
        @Override public LLMResponse complete(String baseUrl, String apiKey, String model,
                                              List<ChatMessage> messages,
                                              List<Map<String, Object>> tools,
                                              double temperature, int maxTokens,
                                              String reasoningEffort) {
            return response("fallback");
        }
    }
}
