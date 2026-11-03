package com.mineagent.modtest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import static com.mineagent.modtest.ModSession.require;
public final class IndustryModGameTests implements FabricGameTest {
    @GameTest(template=FabricGameTest.EMPTY_STRUCTURE,timeoutTicks=100,batch="real-industry")
    public void reportsActualPublicElectricStorageInItsOwnUnit(GameTestHelper helper) {
        try(var session=new ModSession(helper,"ActualEnergyPort")) {
            var machine=new BlockPos(1,1,1);session.place(machine,"techreborn:electric_furnace");
            var observed=session.observe(machine);var caps=observed.getAsJsonObject("capabilities");
            var fields=caps.getAsJsonObject("fields");
            require(caps.getAsJsonObject("channels").get("energy").getAsString().equals("available"),"Real electric machine must expose public energy storage");
            require(fields.get("energy.unit").getAsString().equals("team_reborn_energy"),"Energy unit must come from its actual API");
            require(Long.parseLong(fields.get("energy.capacity").getAsString())>0,"Public capacity must be nonzero");
            require(Long.parseLong(fields.get("energy.amount").getAsString())==0,"An unpowered machine must report zero, not invent energy");
        }helper.succeed();
    }
    @GameTest(template=FabricGameTest.EMPTY_STRUCTURE,timeoutTicks=320,batch="real-industry")
    public void fallsBackFromUnconfiguredPortsAndSmeltsThroughTheRealMenu(GameTestHelper helper) {
        var session=new ModSession(helper,"TechRebornPorts");
        try {
            var machine=new BlockPos(1,1,1);session.place(machine,"techreborn:iron_furnace");
            var observed=session.observe(machine);
            require(observed.get("block_id").getAsString().equals("techreborn:iron_furnace"),"Actual Tech Reborn block must be registered");
            require(observed.has("capabilities"),"Real machine must be inspected through the public bridge");
            var fields=observed.getAsJsonObject("capabilities").getAsJsonObject("fields");
            require(!observed.has("nbt"),"Private state is excluded");
            if(fields.has("energy.unit"))require(fields.get("energy.unit").getAsString().equals("team_reborn_energy"),"Fabric energy must retain its API unit");
            session.carry("minecraft:iron_ore",1,0);
            session.face=net.minecraft.core.Direction.UP;
            session.observe(machine);
            var raw=new java.util.concurrent.atomic.AtomicReference<String>();
            new com.mineagent.tools.inventory.TransferPortTool().onServerCall("closed-port",session.portArgs(machine,"minecraft:iron_ore",0,1,true),session.companion,raw::set);
            var failed=com.google.gson.JsonParser.parseString(raw.get()).getAsJsonObject();
            require(!failed.get("success").getAsBoolean()&&failed.get("moved_count").getAsInt()==0,"Unconfigured port must not fabricate an insertion");
            require(session.player.getInventory().getItem(0).getCount()==1,"Rejected port must conserve the input");
            session.open(machine);session.carry("minecraft:coal",1,1);
            // These slot indices are publicly reported by inspect_gui, never read from a block entity.
            require(session.menu().getAsJsonArray("slots").size()>=3,"Actual furnace menu must expose machine slots");
            session.menuTransfer(0,session.acceptedMenuSlot(0,"input"),1,true);
            session.menuTransfer(1,session.acceptedMenuSlot(1,"fuel"),1,true);
            require(session.player.getInventory().getItem(0).isEmpty()&&session.player.getInventory().getItem(1).isEmpty(),"Menu fallback consumes actual carried inputs");
            helper.runAfterDelay(240,()-> {
                try(session) {
                    require(session.menuCount("minecraft:iron_ingot")==1,"Actual machine must smelt one ingot: "+session.menu());
                    require(session.menuCount("minecraft:iron_ore")==0,"Actual ore must be consumed");
                    session.menuTransfer(session.menuItemSlot("minecraft:iron_ingot"),2,1,false);
                    require(session.player.getInventory().getItem(2).is(net.minecraft.world.item.Items.IRON_INGOT),"Output must arrive in the real player's inventory");
                    helper.succeed();
                }
            });
        } catch(RuntimeException|Error failure) { session.close();throw failure; }
    }
}
