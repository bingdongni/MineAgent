package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.*;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.platform.Services;
import com.mineagent.engine.act.BlockTargeting;
import com.mineagent.engine.entity.CompanionEntity;
import java.util.Map;
import java.util.function.Consumer;

/** Uses a visible public automation port; never reads or writes a mod's private block entity fields. */
public final class TransferPortTool implements Tool {
    @Override public String name() { return "transfer_port"; }
    @Override public String description() {
        return "Move up to 64 actual items between one carried inventory slot and a visible block's public automation port. "
                + "Use into_block or from_block; specify the exact item ID. Requires interaction reach and normal world permission. "
                + "Returns actual moved amount and a fresh public observation. Unavailable ports are an interface gap.";
    }
    @Override public Map<String,Object> parameterSchema() {
        return Schema.object().string("direction", "into_block or from_block")
                .integer("x", "Target X", -30_000_000, 30_000_000)
                .integer("y", "Target Y", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", "Target Z", -30_000_000, 30_000_000)
                .integer("inventory_slot", "Carried main inventory slot", 0, 35)
                .string("item_id", "Exact observed registered item ID")
                .integer("amount", "Maximum number to move", 1, 64)
                .optionalString("face", "Optional observed visible face; learned transfers bind this explicitly")
                .optionalString("dimension", "Optional observed dimension; rejects stale cross-dimension execution").build();
    }
    @Override public void onServerCall(String id, JsonObject args, AgentPlayer player, Consumer<String> reply) {
        Integer x=ToolArgs.getIntOrNull(args,"x"), y=ToolArgs.getIntOrNull(args,"y"), z=ToolArgs.getIntOrNull(args,"z");
        Integer slot=ToolArgs.getIntOrNull(args,"inventory_slot"), amount=ToolArgs.getIntOrNull(args,"amount");
        String direction=ToolArgs.getString(args,"direction", ""), item=ToolArgs.getString(args,"item_id", "");
        if(x==null||y==null||z==null||slot==null||slot<0||slot>35||amount==null||amount<1||amount>64
                || !java.util.Set.of("into_block","from_block").contains(direction)
                || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            reply.accept(ToolArgs.errorJson("Invalid port coordinates, direction, inventory_slot, item_id or amount")); return;
        }
        var sp=((CompanionEntity)player).serverPlayer(); var level=sp.serverLevel();
        String dimension=ToolArgs.getString(args,"dimension",level.dimension().location().toString());
        if(!level.dimension().location().toString().equals(dimension)) {
            reply.accept(ToolArgs.errorJson("Target dimension changed; observe the current world before transferring")); return;
        }
        var pos=new net.minecraft.core.BlockPos(x,y,z);
        String lease=com.mineagent.engine.cognition.TeamWorkBoard.resourceBlocker(player.ownerUuid(),player.companionId(),
                "block:"+dimension+":"+x+","+y+","+z,level.getGameTime());
        if(lease!=null) { reply.accept(ToolArgs.errorJson(lease)); return; }
        if(!level.isInWorldBounds(pos)||!level.isLoaded(pos)||!sp.isAlive()||sp.isRemoved()||sp.isSpectator()||!level.mayInteract(sp,pos)
                || sp.getServer().isUnderSpawnProtection(level,pos,sp)) {
            reply.accept(ToolArgs.errorJson("Public port is unloaded or interaction is not permitted")); return;
        }
        String face=ToolArgs.getString(args,"face",null);
        var side=face==null?null:net.minecraft.core.Direction.byName(face);
        if(face!=null&&side==null) { reply.accept(ToolArgs.errorJson("Invalid public-port face")); return; }
        var hit=side==null?BlockTargeting.findVisibleHit(sp,pos,BlockTargeting.interactionReach(sp))
                :BlockTargeting.findVisibleFace(sp,pos,side,BlockTargeting.interactionReach(sp));
        if(hit.isEmpty()) { reply.accept(ToolArgs.errorJson("Public port requires interaction reach and a visible face")); return; }
        var result=Services.platform().transferBlockItems(player,x,y,z,hit.get().getDirection().getName(),slot,item,amount,direction.equals("into_block"));
        JsonObject response=new JsonObject();
        response.addProperty("success",result.supported()&&result.verified()&&result.moved()>0);
        response.addProperty("postcondition_verified",result.verified()); response.addProperty("moved_count",result.moved());
        response.addProperty("item_id",item); response.addProperty("message",result.message());
        if(!result.supported()||!result.verified()||result.moved()==0)
            response.addProperty("error",result.message().isBlank()?"No items moved":result.message());
        JsonObject inspect=new JsonObject(); inspect.addProperty("x",x);inspect.addProperty("y",y);inspect.addProperty("z",z);inspect.addProperty("capabilities",true);
        inspect.addProperty("face",hit.get().getDirection().getName());
        new com.mineagent.tools.block.InspectBlockTool().onServerCall(id,inspect,player,raw->{
            try { response.add("observation",com.google.gson.JsonParser.parseString(raw)); }
            catch(RuntimeException ignored) { response.addProperty("observation_status","unavailable"); }
        });
        reply.accept(response.toString());
    }
}
