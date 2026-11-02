package com.mineagent.engine.llm;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.ProviderSessionState;
import com.mineagent.api.llm.provider.LLMProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ProviderSessionIsolationTest {
    @Test void builtInFactoriesPreserveProtocolTypeAndIsolateMutableTransport() {
        List<LLMProvider> providers = List.of(OpenAICompatibleProvider.openai(),
                new OpenAIResponsesProvider(), new AnthropicProvider(), new GeminiProvider(),
                new AI21Provider(), new AzureOpenAIProvider(), new ByteDanceArkProvider(),
                new OllamaNativeProvider(), new BedrockConverseProvider(), new BedrockInvokeModelProvider(),
                new BaiduQianfanProvider(), new CohereProvider(), new VertexAIProvider(), new TencentHunyuanProvider(),
                new ProtocolAliasProvider("openai-compatible", "Compatible", OpenAICompatibleProvider.openai(), false));
        for (LLMProvider prototype : providers) {
            LLMProvider first = prototype.newSession();
            LLMProvider second = prototype.newSession();
            assertNotSame(prototype, first, prototype.providerId());
            assertNotSame(first, second, prototype.providerId());
            assertEquals(prototype.getClass(), first.getClass());
            assertEquals(prototype.providerId(), first.providerId());
            assertEquals(prototype.requiresApiKey(), first.requiresApiKey());
        }
        OpenAIResponsesProvider prototype = new OpenAIResponsesProvider();
        LLMProvider first = prototype.newSession();
        LLMProvider second = prototype.newSession();
        first.restoreSessionState(new ProviderSessionState("openai-responses", "model", "companion-a", "response-a", null, 1));
        assertEquals("response-a", first.sessionState("model").previousResponseId());
        assertNull(second.sessionState("model").previousResponseId());
    }

    @Test void unknownSubclassKeepsItsProtocolOverridesAndLegacySynchronization() {
        LLMProvider custom = new AnthropicProvider() {
            @Override public String providerId() { return "custom-anthropic"; }
        };
        assertSame(custom, custom.newSession());
        var alias = new ProtocolAliasProvider("custom", "Custom", custom);
        assertSame(alias, alias.newSession());
    }

    @Test void twoCompanionsCanReachTheSameEndpointWhileHoldingTheirSessionMonitors() throws Exception {
        CountDownLatch arrived = new CountDownLatch(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            arrived.countDown();
            boolean concurrent;
            try { concurrent = arrived.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); concurrent = false; }
            byte[] body = (concurrent
                    ? "{\"id\":\"r\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                    : "{\"error\":{\"message\":\"sessions serialized\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(concurrent ? 200 : 500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var clients = Executors.newFixedThreadPool(2)) {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            LLMProvider prototype = OpenAICompatibleProvider.openai();
            LLMProvider first = prototype.newSession();
            LLMProvider second = prototype.newSession();
            var left = clients.submit(() -> { synchronized (first) {
                return first.complete(endpoint, "test-key", "test-model", List.of(ChatMessage.user("a")), List.of(), 0, 32, null);
            }});
            var right = clients.submit(() -> { synchronized (second) {
                return second.complete(endpoint, "test-key", "test-model", List.of(ChatMessage.user("b")), List.of(), 0, 32, null);
            }});
            assertEquals("ok", left.get(10, TimeUnit.SECONDS).choice().message().content());
            assertEquals("ok", right.get(10, TimeUnit.SECONDS).choice().message().content());
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }
}
