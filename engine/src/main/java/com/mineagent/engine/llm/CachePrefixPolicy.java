package com.mineagent.engine.llm;

import com.google.gson.*;
import com.mineagent.api.llm.LLMRequest;
import java.net.URI;

/** Opt in only on documented official models; arbitrary relays retain their existing wire contract. */
final class CachePrefixPolicy {
    private CachePrefixPolicy() {}
    static boolean supported(String baseUrl,String model) {
        try {
            URI uri=URI.create(baseUrl==null||baseUrl.isBlank()?"https://api.openai.com":baseUrl);
            return uri.getScheme().equalsIgnoreCase("https")&&"api.openai.com".equalsIgnoreCase(uri.getHost())
                    &&model!=null&&model.matches("gpt-(?:5\\.[6-9]|6(?:\\.[0-9]+)?)(?:-.*)?");
        } catch(RuntimeException malformed) { return false; }
    }
    static void applyResponses(LLMRequest request,JsonObject body,int durableInputEnd) {
        if(!supported(request.baseUrl(),request.model())||request.previousResponseId()!=null
                ||request.cacheableMessageCount()==0)return;
        mark(body,"input",durableInputEnd,"input_text");
    }
    static void applyChat(LLMRequest request,JsonObject body,String resolvedBaseUrl) {
        if(!supported(resolvedBaseUrl,request.model())||request.cacheableMessageCount()==0)return;
        mark(body,"messages",request.cacheableMessageCount(),"text");
    }
    private static void mark(JsonObject body,String field,int durableEnd,String textType) {
        if(!body.has(field)||!body.get(field).isJsonArray())return;
        JsonArray messages=body.getAsJsonArray(field);
        int first=-1,last=-1;
        for(int i=0;i<Math.min(durableEnd,messages.size());i++) {
            if(!messages.get(i).isJsonObject())continue;
            var message=messages.get(i).getAsJsonObject();
            if(!message.has("role")||!message.has("content")||!message.get("content").isJsonPrimitive())continue;
            String role=message.get("role").getAsString();
            if(!java.util.Set.of("system","developer","user","assistant","tool").contains(role)||message.get("content").getAsString().isBlank())continue;
            if(first<0)first=i;last=i;
        }
        if(first<0)return;
        for(int index:new int[]{first,last}) {
            JsonObject message=messages.get(index).getAsJsonObject();
            if(!message.get("content").isJsonPrimitive())continue;
            JsonObject text=new JsonObject();text.addProperty("type",textType);text.add("text",message.get("content").deepCopy());
            JsonObject breakpoint=new JsonObject();breakpoint.addProperty("mode","explicit");text.add("prompt_cache_breakpoint",breakpoint);
            JsonArray content=new JsonArray();content.add(text);message.add("content",content);
        }
        JsonObject options=new JsonObject();options.addProperty("mode","explicit");options.addProperty("ttl","30m");
        body.add("prompt_cache_options",options);
    }
}
