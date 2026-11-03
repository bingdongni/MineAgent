package com.mineagent.engine.llm;

import com.google.gson.JsonObject;
import com.mineagent.api.llm.LLMResponse;
import java.math.BigDecimal;

/** Optional accounting fields must not invalidate otherwise valid model output. */
final class UsageParser {
    record Count(int value,boolean reported) {}
    private UsageParser() {}
    static Count count(JsonObject object,String key) {
        if(object==null || !object.has(key) || !object.get(key).isJsonPrimitive()) return new Count(0,false);
        try {
            String text=object.get(key).getAsString();
            if(text.length()>64) return new Count(0,false);
            BigDecimal number=new BigDecimal(text);
            if(number.signum()<0 || number.stripTrailingZeros().scale()>0) return new Count(0,false);
            if(number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))>0) return new Count(Integer.MAX_VALUE,true);
            return new Count(number.intValueExact(),true);
        } catch(RuntimeException invalid) { return new Count(0,false); }
    }
    static int sum(int... values) {
        long total=0; for(int value:values) total+=value;
        return (int)Math.min(Integer.MAX_VALUE,total);
    }
    private static JsonObject object(JsonObject value,String key) {
        return value.has(key) && value.get(key).isJsonObject()?value.getAsJsonObject(key):new JsonObject();
    }
    static LLMResponse.Usage chat(JsonObject value) {
        var details=object(value,"prompt_tokens_details");
        var read=count(details,"cached_tokens"); var relayRead=count(value,"prompt_cache_hit_tokens");
        var write=count(details,"cache_write_tokens");
        int cached=Math.max(read.value(),relayRead.value());
        int input=count(value,"prompt_tokens").value();
        if(input==0 && relayRead.reported()) input=sum(cached,count(value,"prompt_cache_miss_tokens").value());
        return new LLMResponse.Usage(input,count(value,"completion_tokens").value(),count(value,"total_tokens").value(),
                cached,write.value(),read.reported()||relayRead.reported(),write.reported());
    }
    static LLMResponse.Usage responses(JsonObject value) {
        var details=object(value,"input_tokens_details");
        var read=count(details,"cached_tokens"); var write=count(details,"cache_write_tokens");
        return new LLMResponse.Usage(count(value,"input_tokens").value(),count(value,"output_tokens").value(),
                count(value,"total_tokens").value(),read.value(),write.value(),read.reported(),write.reported());
    }
    static LLMResponse.Usage anthropic(JsonObject value) {
        var read=count(value,"cache_read_input_tokens"); var write=count(value,"cache_creation_input_tokens");
        int input=sum(count(value,"input_tokens").value(),read.value(),write.value());
        int output=count(value,"output_tokens").value();
        return new LLMResponse.Usage(input,output,sum(input,output),read.value(),write.value(),read.reported(),write.reported());
    }
}
