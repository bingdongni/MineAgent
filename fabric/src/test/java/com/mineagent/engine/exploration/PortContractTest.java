package com.mineagent.engine.exploration;

import com.google.gson.*;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PortContractTest {
    private static final String TARGET="block:minecraft:overworld:1,64,2";
    private final SemanticWorldModel world=new SemanticWorldModel();
    private final MechanismExplorer explorer=new MechanismExplorer(world,(a,b,c,d)->{});
    PortContractTest() {
        world.observe(TARGET,"block_id","mod:machine",null,1,"server",null,1,100,false);
        world.observe(TARGET,"capability_face","up",null,1,"server",null,1,100,false);
        world.observe(TARGET,"dimension","minecraft:overworld",null,1,"server",null,1,100,false);
        world.observe("inventory:mod:input","count","2",null,1,"server",null,1,100,false);
    }
    @Test void bindsObservedFaceDimensionAndActualInputBeforeAdmittingAProbe() {
        var proposal=propose(args(true),args(false),TARGET);assertTrue(proposal.accepted(),proposal.message());
        var bound=JsonParser.parseString(proposal.experiment().probeArguments()).getAsJsonObject();
        var undo=JsonParser.parseString(proposal.experiment().compensationArguments()).getAsJsonObject();
        assertEquals("up",bound.get("face").getAsString());assertEquals("minecraft:overworld",undo.get("dimension").getAsString());
        assertTrue(proposal.experiment().prerequisites().stream().anyMatch(c->c.subject().equals("inventory:mod:input")&&c.value().equals("1")));
    }
    @Test void actionCannotLearnAgainstAnotherBlockOrAnUnboundToolOutcome() {
        var other=args(true);other.addProperty("x",2);
        assertFalse(propose(other,args(false),TARGET).accepted());
        assertFalse(propose(args(true),args(false),"tool:transfer_port").accepted());
    }
    @Test void changedFaceOrDimensionRequiresNewObservation() {
        var face=args(true);face.addProperty("face","north");assertFalse(propose(face,args(false),TARGET).accepted());
        var dimension=args(true);dimension.addProperty("dimension","minecraft:the_nether");assertFalse(propose(dimension,args(false),TARGET).accepted());
    }
    @Test void missingActualInputCannotBeAssumedFromARecipeOrModelAssertion() {
        world.retract("inventory:mod:input","count","server",null,2,false);
        assertFalse(propose(args(true),args(false),TARGET).accepted());assertTrue(explorer.exportState().experiments().isEmpty());
    }
    @Test void compensationMustReverseTheSameQuantityItemTargetAndCarriedSlot() {
        for(String field:java.util.List.of("x","inventory_slot","amount","item_id","direction")) {
            var undo=args(false);
            if(field.equals("item_id"))undo.addProperty(field,"mod:other");
            else if(field.equals("direction"))undo.addProperty(field,"into_block");else undo.addProperty(field,10);
            assertFalse(propose(args(true),undo,TARGET).accepted(),field);
        }
    }
    @Test void malformedPortArgumentsAreRejectedBeforeSpendingTheProbeBudget() {
        for(String field:java.util.List.of("x","inventory_slot","amount")) {
            var bad=args(true);bad.addProperty(field,1.5);assertFalse(propose(bad,args(false),TARGET).accepted(),field);
        }
        var direction=args(true);direction.addProperty("direction","sideways");assertFalse(propose(direction,args(false),TARGET).accepted());
    }
    private MechanismExplorer.Proposal propose(JsonObject action,JsonObject undo,String target) {
        return explorer.propose("mod:machine","insertion changes public count","transfer_port",action.toString(),target,"capability.items.0.count","1",
                MechanismExplorer.Risk.MEDIUM,"physical",true,"transfer_port",undo.toString(),4,1,2);
    }
    private static JsonObject args(boolean into) {
        var args=new JsonObject();args.addProperty("x",1);args.addProperty("y",64);args.addProperty("z",2);
        args.addProperty("item_id","mod:input");args.addProperty("inventory_slot",0);args.addProperty("amount",1);
        args.addProperty("direction",into?"into_block":"from_block");return args;
    }
}
