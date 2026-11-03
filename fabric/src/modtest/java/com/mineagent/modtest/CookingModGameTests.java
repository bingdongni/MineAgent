package com.mineagent.modtest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import static com.mineagent.modtest.ModSession.require;
public final class CookingModGameTests implements FabricGameTest {
    @GameTest(template=FabricGameTest.EMPTY_STRUCTURE,timeoutTicks=300,batch="real-cooking")
    public void cooksTheRealNewIngredientWithHeatAndAContainer(GameTestHelper helper) {
        var session=new ModSession(helper,"ActualCooking");
        try {
            var pot=new BlockPos(1,2,1);session.place(pot.below(),"minecraft:campfire");session.place(pot,"farmersdelight:cooking_pot");
            session.open(pot);session.carry("farmersdelight:rice",1,0);session.carry("minecraft:bowl",1,1);
            session.menuTransfer(0,session.acceptedMenuSlot(0,"input"),1,true);
            // The visible vessel slot has an anonymous Java class: public layout, not a class-name guess, binds it.
            session.menuTransfer(1,session.menuSlotAt(92,55),1,true);
            helper.runAfterDelay(180,()-> {
                try(session) {
                    require(session.menuCount("farmersdelight:cooked_rice")==1,"Real recipe must cook the unfamiliar ingredient: "+session.menu());
                    require(session.menuCount("farmersdelight:rice")==0,"The real ingredient must be consumed");
                    session.menuTransfer(session.menuItemSlot("farmersdelight:cooked_rice"),2,1,false);
                    require(session.player.getInventory().getItem(2).getCount()==1,"Real cooked output must enter the player's inventory");
                    helper.succeed();
                }
            });
        } catch(RuntimeException|Error failure) { session.close();throw failure; }
    }
    @GameTest(template=FabricGameTest.EMPTY_STRUCTURE,timeoutTicks=100,batch="real-cooking")
    public void observesActualCookingPotAndNewIngredient(GameTestHelper helper) {
        try(var session=new ModSession(helper,"CookingObservation")) {
            var pot=new BlockPos(1,1,1);session.place(pot,"farmersdelight:cooking_pot");
            var observed=session.observe(pot);
            require(observed.get("block_id").getAsString().equals("farmersdelight:cooking_pot"),"Actual Farmer's Delight pot must be registered");
            session.carry("farmersdelight:rice",1,0);
            var args=new com.google.gson.JsonObject();args.addProperty("inventory_slot",0);
            var raw=new java.util.concurrent.atomic.AtomicReference<String>();
            new com.mineagent.tools.perception.InspectItemTool().onServerCall("rice",args,session.companion,raw::set);
            var data=com.google.gson.JsonParser.parseString(raw.get()).getAsJsonObject();
            require(data.get("item_id").getAsString().equals("farmersdelight:rice"),"Real new ingredient must be observed");
            require(!data.has("components"),"Private item components are excluded");
        }
        helper.succeed();
    }
}
