package com.mineagent.engine.llm;

import com.google.gson.*;
import com.mineagent.api.llm.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class CachePrefixPolicyTest {
    @Test void actualResponsesSerializerKeepsTheDeclaredBoundaryAfterExpandedToolCalls() {
        var messages=List.of(ChatMessage.system("stable instructions"),ChatMessage.assistantWithTools(List.of(
                new ChatMessage.ToolCallRef("call","inspect_block","{}"))),ChatMessage.user("durable goal"),ChatMessage.user("volatile live world"));
        var request=new LLMRequest("https://api.openai.com","dummy","gpt-6.1-sol",messages,messages,List.of(),List.of(),0,64,null,null,
                "conversation","cache",Duration.ofSeconds(1),false,3);
        var body=new OpenAIResponsesProvider(OpenAICompatibleProvider.openai()).buildBody(request);
        assertTrue(marked(body,0));assertTrue(marked(body,2));assertFalse(marked(body,3));
        assertEquals("function_call",body.getAsJsonArray("input").get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("volatile live world",body.getAsJsonArray("input").get(3).getAsJsonObject().get("content").getAsString());
        assertFalse(body.toString().contains("prewarm"));
    }
    @Test void declaredDurablePrefixExcludesVolatileSuffixFromExplicitWrites() {
        var request=request("https://api.openai.com/v1","gpt-6.1-sol",3,null);
        var body=body("input","changing world one");CachePrefixPolicy.applyResponses(request,body,3);
        assertEquals("explicit",body.getAsJsonObject("prompt_cache_options").get("mode").getAsString());
        assertTrue(marked(body,0));assertFalse(marked(body,1));assertTrue(marked(body,2));assertFalse(marked(body,3));
        assertEquals("changing world one",body.getAsJsonArray("input").get(3).getAsJsonObject().get("content").getAsString());
    }
    @Test void liveWorldChangesDoNotChangeTheReusablePrefix() {
        var request=request(null,"gpt-6-astra",3,null);var a=body("input","one");var b=body("input","two");
        CachePrefixPolicy.applyResponses(request,a,3);CachePrefixPolicy.applyResponses(request,b,3);
        assertEquals(a.getAsJsonArray("input").get(0),b.getAsJsonArray("input").get(0));
        assertEquals(a.getAsJsonArray("input").get(2),b.getAsJsonArray("input").get(2));
    }
    @Test void relaysLocalEndpointsAndUnknownModelsKeepTheirExistingContract() {
        for(String base:List.of("https://relay.example/v1","http://127.0.0.1:1234","https://api.openai.com.example/v1")) {
            var body=body("input","world");CachePrefixPolicy.applyResponses(request(base,"gpt-6-astra",3,null),body,3);
            assertFalse(body.has("prompt_cache_options"),base);assertTrue(body.getAsJsonArray("input").get(0).getAsJsonObject().get("content").isJsonPrimitive());
        }
        for(String model:List.of("gpt-5.5","llama3","custom")) {
            var body=body("input","world");CachePrefixPolicy.applyResponses(request(null,model,3,null),body,3);
            assertFalse(body.has("prompt_cache_options"),model);
        }
    }
    @Test void undeclaredAndIncrementalBoundariesDoNotDisableImplicitCaching() {
        var body=body("input","world");CachePrefixPolicy.applyResponses(request(null,"gpt-6-astra",0,null),body,3);
        assertFalse(body.has("prompt_cache_options"));
        CachePrefixPolicy.applyResponses(request(null,"gpt-6-astra",3,"previous"),body,3);
        assertFalse(body.has("prompt_cache_options"));
    }
    @Test void chatUsesItsOwnTextBlockSchemaAndKnownEndpoint() {
        var body=body("messages","world");CachePrefixPolicy.applyChat(request(null,"gpt-5.6",3,null),body,"https://api.openai.com");
        var text=body.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("text",text.get("type").getAsString());assertEquals("30m",body.getAsJsonObject("prompt_cache_options").get("ttl").getAsString());
        assertFalse(body.getAsJsonArray("messages").get(3).getAsJsonObject().has("prompt_cache_breakpoint"));
    }
    @Test void noEligibleTextDoesNotEnableAnEmptyExplicitMode() {
        var body=new JsonObject();body.add("input",new JsonArray());CachePrefixPolicy.applyResponses(request(null,"gpt-6-astra",3,null),body,3);
        assertFalse(body.has("prompt_cache_options"));
    }
    private static boolean marked(JsonObject body,int i) {
        var content=body.getAsJsonArray("input").get(i).getAsJsonObject().get("content");
        return content.isJsonArray()&&content.getAsJsonArray().get(0).getAsJsonObject().has("prompt_cache_breakpoint");
    }
    private static LLMRequest request(String base,String model,int boundary,String previous) {
        var messages=List.of(ChatMessage.system("stable instructions"),ChatMessage.user("stable goal"),ChatMessage.assistant("stable reasoning"),ChatMessage.user("live world"));
        return new LLMRequest(base,"dummy",model,messages,messages,List.of(),List.of(),0,64,null,previous,"conversation","cache",Duration.ofSeconds(1),false,boundary);
    }
    private static JsonObject body(String name,String suffix) {
        var body=new JsonObject();var items=new JsonArray();String[] roles={"system","user","assistant","user"};String[] text={"stable instructions","stable goal","stable reasoning",suffix};
        for(int i=0;i<roles.length;i++){var m=new JsonObject();m.addProperty("role",roles[i]);m.addProperty("content",text[i]);items.add(m);}body.add(name,items);return body;
    }
}
