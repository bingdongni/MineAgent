package com.mineagent.modtest;

import com.google.gson.*;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.tools.block.InspectBlockTool;
import com.mineagent.tools.inventory.TransferPortTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.concurrent.atomic.AtomicReference;

final class ModSession implements AutoCloseable {
    final GameTestHelper helper;
    final ServerPlayer player;
    final CompanionEntity companion;
    AgentLoop loop;
    net.minecraft.core.Direction face=net.minecraft.core.Direction.UP;
    ModSession(GameTestHelper helper, String name) { this(helper,name,null); }
    ModSession(GameTestHelper helper, String name, ServerPlayer owner) {
        this.helper=helper; player=helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        companion=new CompanionEntity(player,owner==null?player:owner,name);
        loop=new AgentLoop(companion,"openai-compatible","","offline-native-test","http://127.0.0.1:9",0,64,null);
        loop.pause(); // Block deliberation callbacks while exercising the actual body/skill APIs locally.
    }
    void place(BlockPos relative,String block) {
        var key=ResourceLocation.parse(block);
        require(BuiltInRegistries.BLOCK.containsKey(key),"Required real mod block missing: "+block);
        helper.setBlock(relative,BuiltInRegistries.BLOCK.get(key));
    }
    String subject(BlockPos relative) {
        var p=helper.absolutePos(relative);
        return "block:"+helper.getLevel().dimension().location()+":"+p.getX()+","+p.getY()+","+p.getZ();
    }
    void approach(BlockPos relative) {
        var p=helper.absolutePos(relative);
        player.setPos(p.getX()+0.5+face.getStepX()*2.5,
                p.getY()+0.5+face.getStepY()*2.5-player.getEyeHeight(),p.getZ()+0.5+face.getStepZ()*2.5);
    }
    JsonObject coords(BlockPos relative) {
        var p=helper.absolutePos(relative); var args=new JsonObject();
        args.addProperty("x",p.getX());args.addProperty("y",p.getY());args.addProperty("z",p.getZ());
        args.addProperty("face",face.getName());args.addProperty("dimension",helper.getLevel().dimension().location().toString());return args;
    }
    JsonObject observe(BlockPos relative) {
        approach(relative); var args=coords(relative);args.addProperty("capabilities",true);
        var raw=new AtomicReference<String>();new InspectBlockTool().onServerCall("inspect",args,companion,raw::set);
        var result=JsonParser.parseString(raw.get()).getAsJsonObject();
        require(!result.has("error"),raw.get());
        loop.mechanismExplorer().onToolResult("inspect","inspect_block",true,false,raw.get(),tick());
        loop.refreshServerObservation();
        return result;
    }
    JsonObject portArgs(BlockPos relative,String item,int slot,int amount,boolean into) {
        var args=coords(relative);args.addProperty("direction",into?"into_block":"from_block");
        args.addProperty("item_id",item);args.addProperty("inventory_slot",slot);args.addProperty("amount",amount);return args;
    }
    JsonObject transfer(BlockPos relative,String item,int slot,int amount,boolean into) {
        approach(relative);var raw=new AtomicReference<String>();
        new TransferPortTool().onServerCall("transfer",portArgs(relative,item,slot,amount,into),companion,raw::set);
        var result=JsonParser.parseString(raw.get()).getAsJsonObject();
        require(result.has("success")&&result.get("success").getAsBoolean(),raw.get());return result;
    }
    JsonObject call(com.mineagent.api.agent.tool.Tool tool,JsonObject args) {
        var raw=new AtomicReference<String>();tool.onServerCall(tool.name(),args,companion,raw::set);
        require(raw.get()!=null,"Tool callback is required: "+tool.name());
        var result=JsonParser.parseString(raw.get()).getAsJsonObject();require(!result.has("error"),raw.get());
        loop.refreshServerObservation();loop.mechanismExplorer().onToolResult(tool.name(),tool.name(),true,false,raw.get(),tick());
        return result;
    }
    JsonObject menu() { return call(new com.mineagent.tools.InspectGuiTool(),new JsonObject()); }
    int acceptedMenuSlot(int inventorySlot,String preferredType) {
        var args=new JsonObject();args.addProperty("inventory_slot",inventorySlot);
        var data=call(new com.mineagent.tools.InspectGuiTool(),args);
        var candidates=new java.util.ArrayList<JsonObject>();
        for(var value:data.getAsJsonArray("slots")) {
            var slot=value.getAsJsonObject();
            if(slot.get("endpoint").getAsString().equals("container")&&slot.has("accepts_probe")&&slot.get("accepts_probe").getAsBoolean()
                    &&slot.get("probe_space").getAsInt()>0)candidates.add(slot);
        }
        return candidates.stream().filter(s->s.get("slot_type").getAsString().toLowerCase(java.util.Locale.ROOT).contains(preferredType))
                .findFirst().or(()->candidates.stream().findFirst()).orElseThrow(()->new IllegalStateException("No public accepting slot: "+data)).get("slot").getAsInt();
    }
    int menuItemSlot(String item) {
        var data=menu();for(var value:data.getAsJsonArray("slots")) {
            var slot=value.getAsJsonObject();
            if(slot.get("endpoint").getAsString().equals("container")&&slot.has("item")&&slot.get("item").getAsString().equals(item))return slot.get("slot").getAsInt();
        }throw new net.minecraft.gametest.framework.GameTestAssertException("Public output missing: "+item+" menu="+data);
    }
    int menuSlotAt(int x,int y) {
        for(var value:menu().getAsJsonArray("slots")) {
            var slot=value.getAsJsonObject();
            if(slot.get("endpoint").getAsString().equals("container")&&slot.get("x").getAsInt()==x&&slot.get("y").getAsInt()==y)
                return slot.get("slot").getAsInt();
        }throw new IllegalStateException("Missing visible menu slot at "+x+","+y);
    }
    void open(BlockPos relative) {
        approach(relative);player.getInventory().selected=8;
        var pos=helper.absolutePos(relative);
        var hit=com.mineagent.engine.act.BlockTargeting.findVisibleFace(player,pos,face,player.blockInteractionRange()).orElseThrow();
        player.gameMode.useItemOn(player,helper.getLevel(),player.getMainHandItem(),net.minecraft.world.InteractionHand.MAIN_HAND,hit);
        require(player.containerMenu!=player.inventoryMenu,"Normal interaction must open a real menu");menu();
    }
    void menuTransfer(int from,int to,int count,boolean into) {
        var args=new JsonObject();args.addProperty("from_slot",from);args.addProperty("to_slot",to);args.addProperty("count",count);
        args.addProperty("source",into?"player":"container");args.addProperty("destination",into?"container":"player");
        call(new com.mineagent.tools.inventory.TransferItemsTool(),args);
    }
    int menuCount(String item) {
        int count=0;for(var element:menu().getAsJsonArray("slots")) {
            var slot=element.getAsJsonObject();
            if(slot.get("endpoint").getAsString().equals("container")&&slot.has("item")&&slot.get("item").getAsString().equals(item))
                count+=slot.get("count").getAsInt();
        }return count;
    }
    void carry(String item,int count,int slot) {
        var key=ResourceLocation.parse(item);require(BuiltInRegistries.ITEM.containsKey(key),"Missing real item: "+item);
        player.getInventory().setItem(slot,new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(key),count));
        loop.refreshServerObservation();
    }
    long tick() { return helper.getLevel().getGameTime(); }
    void restartFromJson(String json) {
        loop.shutdown();loop=new AgentLoop(companion,"openai-compatible","","offline-native-test","http://127.0.0.1:9",0,64,null);
        loop.pause();loop.mechanismExplorer().importState(new Gson().fromJson(json,com.mineagent.engine.exploration.MechanismExplorer.State.class));
    }
    static void require(boolean value,String message) { if(!value)throw new net.minecraft.gametest.framework.GameTestAssertException(message.substring(0,Math.min(600,message.length()))); }
    @Override public void close() { try { require(loop.metricsSnapshot().llmCalls()==0,"Native mod tests must make no model calls"); } finally { loop.shutdown(); } }
}
