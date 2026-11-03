package com.mineagent.engine.llm;

import com.google.gson.JsonParser;
import com.mineagent.api.llm.*;
import com.mineagent.engine.telemetry.CognitionMetrics;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class CacheAccountingTest {
    @Test void absentAndExplicitZeroCacheReportsRemainDifferent() {
        var unknown=UsageParser.chat(json("{\"prompt_tokens\":100}"));
        var zero=UsageParser.chat(json("{\"prompt_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0}}"));
        assertFalse(unknown.cacheReadReported()); assertFalse(unknown.cacheWriteReported());
        assertTrue(zero.cacheReadReported()); assertTrue(zero.cacheWriteReported());
    }
    @Test void malformedOptionalUsageDoesNotDiscardAValidChatResponse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            byte[] response=("{\"id\":\"r\",\"model\":\"custom\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    +"\"usage\":{\"prompt_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":null,\"cache_write_tokens\":\"bad\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            var messages=List.of(ChatMessage.user("hello"));
            var response=OpenAICompatibleProvider.openai().complete(new LLMRequest("http://127.0.0.1:"+server.getAddress().getPort(),
                    "test-key","custom",messages,messages,List.of(),List.of(),0,32,null,null,null,null,Duration.ofSeconds(5),false));
            assertEquals("ok",response.choice().message().content());
            assertFalse(response.usage().cacheReadReported()); assertFalse(response.usage().cacheWriteReported());
        } finally { server.stop(0); }
    }
    @Test void hugeProviderCountsSaturateInsteadOfWrappingNegative() {
        var usage=UsageParser.anthropic(json("{\"input_tokens\":2147483647,\"cache_read_input_tokens\":2147483647,\"cache_creation_input_tokens\":2147483647,\"output_tokens\":2147483647}"));
        assertEquals(Integer.MAX_VALUE,usage.promptTokens()); assertEquals(Integer.MAX_VALUE,usage.totalTokens());
        assertTrue(usage.cacheReadReported()); assertTrue(usage.cacheWriteReported());
    }
    @Test void invalidNumbersAreUnknownRatherThanReportedZero() {
        for(String value:List.of("null","-1","true","1.5","2147483648.5","\"bad\"","{}")) {
            var usage=UsageParser.responses(json("{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":"+value+"}}"));
            assertFalse(usage.cacheReadReported(),value); assertEquals(0,usage.cachedPromptTokens());
        }
    }
    @Test void metricsUseObservableTrafficAndExposeItsCoverage() {
        var metrics=new CognitionMetrics();
        metrics.recordLlm(new LLMResponse("one","custom",null,new LLMResponse.Usage(100,1,101,75,0,true,true),"stop"),10,false);
        metrics.recordLlm(new LLMResponse("two","local",null,new LLMResponse.Usage(900,1,901),"stop"),10,false);
        assertEquals(0.75,metrics.snapshot().providerCacheHitRate(),0.00001);
        assertEquals(1,metrics.cacheCoverage().readReportedCalls()); assertEquals(2,metrics.cacheCoverage().calls());
    }
    @Test void relayCountersDoNotDoubleCountTheirOverlappingInput() {
        var usage=UsageParser.chat(json("{\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20,\"completion_tokens\":2}"));
        assertEquals(100,usage.promptTokens()); assertEquals(80,usage.cachedPromptTokens()); assertTrue(usage.cacheReadReported());
        var nativeUsage=UsageParser.anthropic(json("{\"input_tokens\":20,\"cache_read_input_tokens\":80,\"cache_creation_input_tokens\":10,\"output_tokens\":2}"));
        assertEquals(110,nativeUsage.promptTokens()); assertEquals(112,nativeUsage.totalTokens());
    }
    private static com.google.gson.JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
