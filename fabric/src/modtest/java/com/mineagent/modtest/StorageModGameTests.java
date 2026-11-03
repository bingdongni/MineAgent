package com.mineagent.modtest;

import com.mineagent.engine.exploration.MechanismExplorer;
import com.mineagent.engine.skill.SkillRuntime;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import static com.mineagent.modtest.ModSession.require;

public final class StorageModGameTests implements FabricGameTest {
    @GameTest(template=FabricGameTest.EMPTY_STRUCTURE,timeoutTicks=100,batch="real-storage")
    public void learnsCompensatesAndTransfersARealDrawer(GameTestHelper helper) {
        try(var session=new ModSession(helper,"DrawerLearning")) {
            var a=new BlockPos(1,1,1);var b=new BlockPos(3,1,1);var target=new BlockPos(1,1,3);
            for(var p:java.util.List.of(a,b,target))session.place(p,"extended_drawers:single_drawer");
            session.carry("minecraft:diamond",3,0);
            for(var p:java.util.List.of(a,b)) {
                var observed=session.observe(p);
                require(observed.getAsJsonObject("capabilities").getAsJsonObject("channels").get("items").getAsString().equals("available"),"Real drawer must expose its public port");
                var probe=session.portArgs(p,"minecraft:diamond",0,1,true);
                var undo=session.portArgs(p,"minecraft:diamond",0,1,false);
                var proposal=session.loop.mechanismExplorer().propose("extended_drawers:single_drawer","inserting one diamond increases public count to one",
                        "transfer_port",probe.toString(),session.subject(p),"capability.items.0.count","1",MechanismExplorer.Risk.MEDIUM,
                        "native-drawer",true,"transfer_port",undo.toString(),4,1,session.tick());
                require(proposal.accepted(),proposal.message());
                var result=session.loop.runMechanismProbe(proposal.experiment().id());
                require(result.accepted(),result.evidence());
                require(session.loop.mechanismExplorer().get(proposal.experiment().id()).status()==MechanismExplorer.Status.SUPPORTED,"Real physical port delta must support the contract");
                require(session.player.getInventory().getItem(0).getCount()==3,"Exact reverse port compensation must restore carried items");
            }
            var rule=session.loop.mechanismExplorer().knowledgeBase().rules().iterator().next();
            require(rule.reusable(session.loop.mechanismExplorer().knowledgeBase().currentFingerprint()),"Two independent real drawers must establish reusable evidence");
            var environment=session.loop.mechanismExplorer().knowledgeBase().currentFingerprint().toString();
            session.loop.mechanismExplorer().taskMemory().begin("store diamonds in extended_drawers:single_drawer",environment,session.tick());
            session.loop.mechanismExplorer().taskMemory().outcome("drawer-evidence","learn drawer insertion",true,"two real instances, exact reverse compensation",session.tick());
            session.loop.mechanismExplorer().taskMemory().begin("survey an unrelated furnace",environment,session.tick());
            var other=new BlockPos(3,1,3);session.place(other,"minecraft:furnace");session.observe(other);
            String json=new com.google.gson.Gson().toJson(session.loop.mechanismExplorer().exportState());
            session.restartFromJson(json);
            require(!session.loop.mechanismExplorer().transfer(rule.id(),session.subject(target),helper.getLevel().dimension().location().toString(),session.tick()).eligible(),"Historical memory cannot substitute for observing a new target");
            require(!session.loop.mechanismExplorer().taskMemory().render("store diamonds",environment,1600).isEmpty(),"Task A evidence must remain after unrelated task B and JSON/runtime restart");
            session.face=net.minecraft.core.Direction.SOUTH;session.observe(target);
            require(!session.loop.mechanismExplorer().transfer(rule.id(),session.subject(target),helper.getLevel().dimension().location().toString(),session.tick()).eligible(),"A learned upper port must not silently transfer to a different face");
            session.face=net.minecraft.core.Direction.UP;
            session.observe(target);
            var transfer=session.loop.mechanismExplorer().transfer(rule.id(),session.subject(target),helper.getLevel().dimension().location().toString(),session.tick());
            require(transfer.eligible(),transfer.message());
            require(session.loop.skillRuntime().start(transfer.skillName(),transfer.overrides(),session.tick()).accepted(),"Real learned skill must start");
            session.loop.skillRuntime().tick(session.tick());session.loop.skillRuntime().tick(session.tick()+1);
            require(session.loop.skillRuntime().snapshot().status()==SkillRuntime.Status.SUCCEEDED,"Transferred real drawer effect must verify");
            require(session.player.getInventory().getItem(0).getCount()==2,"Transferred execution must move exactly one actual diamond");
        }
        helper.succeed();
    }
}
