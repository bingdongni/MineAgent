package com.mineagent.engine.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.llm.ChatMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class AnthropicCachePrefixTest {
    @Test void durableToolResultIsCachedBeforeMergedVolatileUserContent() throws Exception {
        List<JsonObject> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response = "{\"id\":\"r\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AnthropicProvider provider = new AnthropicProvider();
            for (String live : List.of("[LIVE] tick=1", "[LIVE] tick=2")) {
                List<ChatMessage> messages = List.of(ChatMessage.system("static instructions"), ChatMessage.user("goal"),
                        ChatMessage.assistant(null, List.of(new ChatMessage.ToolCallRef("look", "look_around", "{}"))),
                        ChatMessage.toolResult("look", "{\"observed\":true}"), ChatMessage.user(live));
                provider.complete(new com.mineagent.api.llm.LLMRequest(
                        "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "custom-model", messages, messages,
                        List.of(), List.of(), 0, 32, null, null, null, null, java.time.Duration.ofSeconds(5), false, 4));
            }
            assertEquals(2, requests.size());
            var first = requests.get(0).getAsJsonArray("messages");
            var second = requests.get(1).getAsJsonArray("messages");
            assertEquals(3, first.size()); // tool_result and volatile user text share one wire message.
            var firstTail = first.get(2).getAsJsonObject().getAsJsonArray("content");
            var secondTail = second.get(2).getAsJsonObject().getAsJsonArray("content");
            assertEquals("tool_result", firstTail.get(0).getAsJsonObject().get("type").getAsString());
            assertTrue(firstTail.get(0).getAsJsonObject().has("cache_control"));
            assertEquals(firstTail.get(0), secondTail.get(0));
            assertFalse(firstTail.get(1).getAsJsonObject().has("cache_control"));
            assertNotEquals(firstTail.get(1), secondTail.get(1));
            assertTrue(requests.get(0).getAsJsonArray("system").get(0).getAsJsonObject().has("cache_control"));
            assertFalse(requests.get(0).has("cacheableMessageCount"));
        } finally { server.stop(0); }
    }

    @Test void summaryChangesPreserveTheExactCacheableInstructionBlock() throws Exception {
        List<JsonObject> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response = "{\"id\":\"r\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":20,\"output_tokens\":2}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AnthropicProvider provider = new AnthropicProvider();
            for (String summary : List.of("[SUMMARY] old task", "[SUMMARY] new task")) {
                provider.complete("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "custom-model",
                        List.of(ChatMessage.system("immutable instructions"), ChatMessage.system(summary), ChatMessage.user("continue")),
                        List.of(), 0, 32, null);
            }
            assertEquals(2, requests.size());
            var first = requests.get(0).getAsJsonArray("system");
            var second = requests.get(1).getAsJsonArray("system");
            assertEquals(2, first.size());
            assertEquals(first.get(0), second.get(0));
            assertEquals("ephemeral", first.get(0).getAsJsonObject().getAsJsonObject("cache_control").get("type").getAsString());
            assertFalse(first.get(1).getAsJsonObject().has("cache_control"));
            assertNotEquals(first.get(1), second.get(1));
        } finally { server.stop(0); }
    }
}
