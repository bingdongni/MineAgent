package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.tools.inventory.UseItemTool;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/** Executes one vanilla item-use lifecycle and verifies that it was accepted. */
public final class UseItemTask extends CompanionTask<UseItemTool.UseItemTaskRecord>
        implements IntentAwareTask {
    private enum Phase { START, USING, DONE }

    private Phase phase;
    private int useTicks;
    private boolean enteredUseState;
    private boolean interruptedDuringUse;
    private String usedItemId;
    private String failReason;

    public UseItemTask(AgentPlayer player, UseItemTool.UseItemTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.START;
        useTicks = 0;
        enteredUseState = false;
        interruptedDuringUse = false;
        usedItemId = null;
        failReason = null;
        beginUse();
    }

    @Override
    protected void onResume() {
        if (!interruptedDuringUse) return;
        // Survival preemption calls stopUsingItem, which destroys vanilla's
        // continuous-use state. Resuming the old phase would either report an
        // interrupted meal as success or release an uncharged bow. Restart the
        // one use lifecycle from authoritative inventory state instead.
        interruptedDuringUse = false;
        useTicks = 0;
        enteredUseState = false;
        beginUse();
    }

    private void beginUse() {
        var sp = TaskContext.serverPlayer(player);
        InteractionHand hand = hand();
        if (record.itemId != null
                && !TaskContext.selectInventoryItemForHand(player, record.itemId, hand)) {
            failReason = "Required item '" + record.itemId + "' is not in inventory";
            phase = Phase.DONE;
            return;
        }
        var held = sp.getItemInHand(hand);
        if (held.isEmpty()) {
            failReason = "The selected hand is empty";
            phase = Phase.DONE;
            return;
        }
        usedItemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        aim();
        if (sp.isUsingItem()) sp.stopUsingItem();
        if (!Interaction.useItem(sp, hand)) {
            failReason = "Item rejected untargeted vanilla use";
            phase = Phase.DONE;
            return;
        }
        enteredUseState = sp.isUsingItem();
        phase = enteredUseState ? Phase.USING : Phase.DONE;
        TaskContext.syncInventory(sp);
    }

    @Override
    protected TaskState onTick() {
        var sp = TaskContext.serverPlayer(player);
        if (record.deadline() > 0L && sp.level().getGameTime() >= record.deadline()) {
            failReason = "Item use exceeded its deadline";
            cleanup(false);
            return TaskState.FAILED;
        }
        if (phase == Phase.DONE) return failReason == null
                ? TaskState.SUCCESS : TaskState.FAILED;
        if (phase != Phase.USING) return TaskState.RUNNING;

        aim();
        if (!sp.isUsingItem()) {
            // Natural-duration items complete inside vanilla's player tick.
            // Losing the use state before an explicit requested hold duration
            // is evidence of interruption, not success.
            if (record.holdTicks > 0 && useTicks < record.holdTicks) {
                failReason = "Item use stopped before the requested hold duration";
            }
            phase = Phase.DONE;
            TaskContext.syncInventory(sp);
            return failReason == null ? TaskState.SUCCESS : TaskState.FAILED;
        }
        useTicks++;
        if (record.holdTicks > 0 && useTicks >= record.holdTicks) {
            sp.releaseUsingItem();
            TaskContext.syncInventory(sp);
            phase = Phase.DONE;
            return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }

    private void aim() {
        if (record.targetX == null) return;
        var sp = TaskContext.serverPlayer(player);
        sp.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(record.targetX, record.targetY, record.targetZ));
    }

    private InteractionHand hand() {
        return "offhand".equals(record.hand)
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    private void cleanup(boolean release) {
        var sp = TaskContext.serverPlayer(player);
        if (sp.isUsingItem()) {
            if (release) sp.releaseUsingItem();
            else sp.stopUsingItem();
        }
        TaskContext.inputDriver(player).clear();
        TaskContext.syncInventory(sp);
    }

    @Override
    protected void onInterrupt() {
        var sp = TaskContext.serverPlayer(player);
        // An immediate throw is already DONE and must never be repeated after
        // a survival pause. Only a genuinely active vanilla use is resumable.
        interruptedDuringUse = phase == Phase.USING && sp.isUsingItem();
        cleanup(false);
    }

    @Override
    public TaskSnapshot snapshot() {
        String stage = phase == null ? "initializing"
                : phase.name().toLowerCase(java.util.Locale.ROOT);
        int total = record.holdTicks > 0 ? record.holdTicks : -1;
        return TaskSnapshot.progress(stage, "Using "
                        + (usedItemId == null ? "held item" : usedItemId),
                useTicks, total, null, null, null,
                phase == Phase.DONE ? failReason : null,
                "vanilla_use_accepted=" + (enteredUseState || phase == Phase.DONE),
                ((long) useTicks << 2) ^ (phase == null ? 0L : phase.ordinal()));
    }

    @Override
    public IntentContract intentContract() {
        return new IntentContract("Use " + (record.itemId == null
                ? "the held item" : record.itemId),
                "Vanilla accepts the use and any requested hold/release lifecycle completes",
                null, null, null, IntentContract.TerrainPolicy.CONSERVATIVE,
                java.util.List.of());
    }

    @Override protected String successMessage() {
        return "Used " + (usedItemId == null ? "held item" : usedItemId)
                + (record.holdTicks > 0 ? " for " + useTicks + " ticks" : "");
    }

    @Override protected String failureMessage() {
        return failReason == null ? "Item use failed" : failReason;
    }

    @Override protected String timeoutMessage() {
        return "Item use timed out after " + useTicks + " ticks";
    }
}
