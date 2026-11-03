package com.mineagent.modtest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import static com.mineagent.modtest.ModSession.require;
public final class CrossModGameTests implements FabricGameTest {
    @GameTest(template=FabricGameTest.EMPTY_STRUCTURE,timeoutTicks=100,batch="real-cross-mod")
    public void twoAgentsCoordinateDependentJobsOnOneRealPort(GameTestHelper helper) {
        try(var a=new ModSession(helper,"Supplier");var b=new ModSession(helper,"Receiver",a.player)) {
            var drawer=new BlockPos(1,1,1);a.place(drawer,"extended_drawers:single_drawer");a.carry("farmersdelight:rice",2,0);
            var owner=a.companion.ownerUuid();var first=a.companion.companionId();var second=b.companion.companionId();
            var deposit=new com.mineagent.engine.planning.PlanGraph.GoalCondition(a.subject(drawer),"capability.items.0.count","2",com.mineagent.engine.planning.PlanGraph.Comparison.EQUALS,0.7);
            var receive=new com.mineagent.engine.planning.PlanGraph.GoalCondition("inventory:farmersdelight:rice","count","2",com.mineagent.engine.planning.PlanGraph.Comparison.EQUALS,0.7);
            require(com.mineagent.engine.cognition.TeamWorkBoard.post(owner,first,"deposit","supply rice",a.subject(drawer),java.util.List.of(),java.util.List.of(deposit),a.tick()).accepted(),"Post immutable supply job");
            require(com.mineagent.engine.cognition.TeamWorkBoard.post(owner,first,"receive","receive supplied rice",a.subject(drawer),java.util.List.of("deposit"),java.util.List.of(receive),a.tick()).accepted(),"Post dependent receiving job");
            require(!com.mineagent.engine.cognition.TeamWorkBoard.claim(owner,second,"receive",a.tick()).accepted(),"Dependent work cannot start before supply is verified");
            require(com.mineagent.engine.cognition.TeamWorkBoard.claim(owner,first,"deposit",a.tick()).accepted(),"Supplier must acquire the public resource lease");
            require(!com.mineagent.engine.cognition.TeamWorkBoard.complete(owner,first,"deposit",a.loop.semanticWorldModel(),a.tick()).accepted(),"A job cannot complete without its physical effect");
            b.approach(drawer);var raw=new java.util.concurrent.atomic.AtomicReference<String>();
            new com.mineagent.tools.inventory.TransferPortTool().onServerCall("conflict",b.portArgs(drawer,"farmersdelight:rice",0,1,false),b.companion,raw::set);
            require(com.google.gson.JsonParser.parseString(raw.get()).getAsJsonObject().has("error"),"Another agent's port write must respect the active lease");
            a.transfer(drawer,"farmersdelight:rice",0,2,true);a.observe(drawer);
            require(com.mineagent.engine.cognition.TeamWorkBoard.complete(owner,first,"deposit",a.loop.semanticWorldModel(),a.tick()).accepted(),"Fresh real drawer count verifies supply");
            require(com.mineagent.engine.cognition.TeamWorkBoard.claim(owner,second,"receive",b.tick()).accepted(),"Verified predecessor releases the port to the receiver");
            b.transfer(drawer,"farmersdelight:rice",0,2,false);b.observe(drawer);
            require(com.mineagent.engine.cognition.TeamWorkBoard.complete(owner,second,"receive",b.loop.semanticWorldModel(),b.tick()).accepted(),"Receiver must observe its own actual inventory");
            require(a.player.getInventory().getItem(0).isEmpty()&&b.player.getInventory().getItem(0).getCount()==2,"Team handoff conserves exactly two real ingredients");
        }helper.succeed();
    }
    @GameTest(template=FabricGameTest.EMPTY_STRUCTURE,timeoutTicks=100,batch="real-cross-mod")
    public void movesARealModIngredientThroughAnotherModsPort(GameTestHelper helper) {
        try(var session=new ModSession(helper,"CrossModIngredient")) {
            var drawer=new BlockPos(1,1,1);session.place(drawer,"extended_drawers:single_drawer");
            session.carry("farmersdelight:rice",4,0);
            session.transfer(drawer,"farmersdelight:rice",0,4,true);
            require(session.player.getInventory().getItem(0).isEmpty(),"Cross-mod deposit must conserve all four ingredients");
            session.transfer(drawer,"farmersdelight:rice",1,3,false);
            require(session.player.getInventory().getItem(1).getCount()==3,"Cross-mod retrieval must respect requested amount");
            session.transfer(drawer,"farmersdelight:rice",0,1,false);
            require(session.player.getInventory().getItem(0).getCount()==1,"Remaining ingredient must be retained, not duplicated or lost");
        }
        helper.succeed();
    }
}
