package com.mineagent.api.platform;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CapabilityObservationTest {
    @Test void boundedFieldsKeepUnknownChannelsDistinctFromZero() {
        var observation=CapabilityObservation.unsupported(); assertTrue(observation.fields().isEmpty());
        assertEquals("unavailable",observation.channels().get("adapter"));
    }
    @Test void oversizedAndNonSchemaFieldsAreExcludedAndReported() {
        var fields=new java.util.LinkedHashMap<String,String>();
        for(int i=0;i<150;i++) fields.put("items."+i+".count","0");
        fields.put("not a field","hidden"); fields.put("energy.secret","x".repeat(257));
        var value=new CapabilityObservation(fields,Map.of(),false);
        assertEquals(96,value.fields().size()); assertTrue(value.truncated());
        assertFalse(value.fields().containsKey("energy.secret"));
    }
    @Test void snapshotCannotBeChangedByAModAfterPublication() {
        var fields=new java.util.HashMap<String,String>(); fields.put("energy.amount","10");
        var value=new CapabilityObservation(fields,Map.of("energy","available"),false); fields.put("energy.amount","0");
        assertEquals("10",value.fields().get("energy.amount"));
        assertThrows(UnsupportedOperationException.class,()->value.fields().put("x","y"));
    }
}
