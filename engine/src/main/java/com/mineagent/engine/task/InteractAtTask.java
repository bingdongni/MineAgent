package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.world.WorldAssetIndex;
import com.mineagent.engine.world.WorldAssetObserver;
import com.mineagent.tools.InteractAtTool;
import net.minecraft.core.BlockPos;

/** Navigates to and verifies a block use or progressive block attack. */
public class InteractAtTask extends CompanionTask<InteractAtTool.InteractAtTaskRecord>
        implements IntentAwareTask {
    private enum Phase { NAVIGATE, INTERACT, DONE }

    private static final int MAX_HOLD_TICKS = 40;
    private PlayerNav nav;
    private Phase phase;
    private int interactTicks;
    private int breakTimeoutTicks;
    private String failReason;
    private boolean breakStarted;

    public InteractAtTask(AgentPlayer player, InteractAtTool.InteractAtTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        interactTicks = 0;
        breakTimeoutTicks = Integer.MAX_VALUE;
        failReason = null;
        breakStarted = false;
        nav = new PlayerNav(player, TaskContext.navCaches(player),
                intentContract().terrainPolicy());
        nav.setListener(new PlayerNav.NavListener() {
            @Override public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.INTERACT;
            }
            @Override public void onNavigationFailed(String reason) {
                failReason = "Navigation to block failed: " + reason;
                phase = Phase.DONE;
            }
        });
        nav.navigateToBlock(record.x, record.y, record.z);
    }

    @Override
    protected TaskState onTick() {
        long now = TaskContext.serverPlayer(player).level().getGameTime();
        if (record.deadline() > 0L && now >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }
        switch (phase) {
            case NAVIGATE -> nav.tick();
            case INTERACT -> tickInteract();
            case DONE -> { }
        }
        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        return phase == Phase.DONE ? TaskState.SUCCESS : TaskState.RUNNING;
    }

    private void tickInteract() {
        var sp = TaskContext.serverPlayer(player);
        lookAtTarget();
        var hand = "use_offhand".equals(record.button)
                ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND;
        if (record.itemId != null
                && !TaskContext.selectInventoryItemForHand(player, record.itemId, hand)) {
            failReason = "Required item '" + record.itemId + "' is not in inventory";
            phase = Phase.DONE;
            return;
        }

        BlockPos target = new BlockPos(record.x, record.y, record.z);
        if ("attack".equals(record.button)) {
            if (sp.level().getBlockState(target).isAir()) {
                breakStarted = false;
                invalidateWorldAsset(target);
                phase = Phase.DONE;
                return;
            }
            if (!breakStarted) {
                BlockDigger.BreakAssessment assessment =
                        BlockDigger.startBreakingDetailed(sp, target);
                if (!assessment.allowed()) {
                    // Preserve the executor's exact evidence. The old generic
                    // message caused the model to invent protection regions
                    // for ordinary reach and line-of-sight failures.
                    failReason = "Could not start block attack: "
                            + assessment.diagnostic();
                    phase = Phase.DONE;
                    return;
                }
                breakTimeoutTicks = BlockDigger.expectedBreakTicks(sp, target);
                breakStarted = true;
            }
            if (++interactTicks > breakTimeoutTicks) {
                BlockDigger.abortBreaking(sp, target);
                breakStarted = false;
                failReason = "Block did not break before interaction timeout";
                phase = Phase.DONE;
            }
            return;
        }

        int requiredTicks = Math.max(1, Math.min(record.holdTicks, MAX_HOLD_TICKS));
        if (interactTicks == 0) {
            boolean accepted = switch (record.button) {
                case "use" -> Interaction.interactBlock(sp, target,
                        net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction();
                case "use_offhand" -> Interaction.interactBlock(sp, target,
                        net.minecraft.world.InteractionHand.OFF_HAND).consumesAction();
                default -> false;
            };
            if (!accepted) {
                failReason = "Block rejected the requested interaction";
                phase = Phase.DONE;
                return;
            }
            recordInteractionEvidence(target);
        }

        if (++interactTicks >= requiredTicks) {
            TaskContext.inputDriver(player).clear();
            // Releasing charged items invokes their vanilla releaseUsing
            // behavior; stopUsingItem merely cancels and could never fire a
            // bow/trident after a requested hold duration.
            if (sp.isUsingItem()) sp.releaseUsingItem();
            phase = Phase.DONE;
        }
    }

    /**
     * Learn generic affordances from an interaction that vanilla accepted.
     * This is especially important for modded block entities: the agent need
     * not know their class in advance to remember that the object opens a menu
     * and which items were actually visible there.
     */
    private void recordInteractionEvidence(BlockPos target) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) return;
        var sp = TaskContext.serverPlayer(player);
        WorldAssetObserver.rememberInteractionTarget(sp, target);
        var state = sp.level().getBlockState(target);
        if (!state.isAir()) {
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).toString();
            var blockItem = state.getBlock().asItem();
            String itemId = blockItem == net.minecraft.world.item.Items.AIR ? blockId
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(blockItem).toString();
            java.util.LinkedHashSet<String> capabilities = new java.util.LinkedHashSet<>();
            capabilities.add("interact");
            if (state.hasBlockEntity()) capabilities.add("inspect");
            if (sp.containerMenu != null && sp.containerMenu != sp.inventoryMenu) {
                capabilities.add("open_menu");
            }
            loop.worldAssetIndex().observeWorldObject(
                    new WorldAssetIndex.WorldObservation(blockId, itemId,
                            "interacted_block", new WorldAssetIndex.Position(
                            sp.level().dimension().location().toString(),
                            target.getX(), target.getY(), target.getZ()),
                            1, 0, 0, capabilities, 0.0, 1.0),
                    sp.level().getGameTime());
        }
        if (sp.containerMenu != null && sp.containerMenu != sp.inventoryMenu) {
            WorldAssetObserver.observeOpenMenu(loop.worldAssetIndex(), sp,
                    sp.containerMenu);
        }
    }

    private void invalidateWorldAsset(BlockPos target) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) return;
        var sp = TaskContext.serverPlayer(player);
        loop.worldAssetIndex().invalidatePosition(new WorldAssetIndex.Position(
                sp.level().dimension().location().toString(), target.getX(),
                target.getY(), target.getZ()));
    }

    private void lookAtTarget() {
        var sp = TaskContext.serverPlayer(player);
        var eye = sp.getEyePosition();
        double dx = record.x + 0.5 - eye.x;
        double dy = record.y + 0.5 - eye.y;
        double dz = record.z + 0.5 - eye.z;
        sp.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        sp.setXRot((float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        if (breakStarted) {
            BlockDigger.abortBreaking(TaskContext.serverPlayer(player),
                    new BlockPos(record.x, record.y, record.z));
            breakStarted = false;
        }
        TaskContext.inputDriver(player).clear();
        TaskContext.serverPlayer(player).stopUsingItem();
    }

    @Override protected void onInterrupt() { cancelNav(); }
    @Override public TaskSnapshot snapshot() {
        String stage = phase == null ? "initializing"
                : phase.name().toLowerCase(java.util.Locale.ROOT);
        int required = "attack".equals(record.button)
                ? Math.max(1, breakTimeoutTicks)
                : Math.max(1, Math.min(record.holdTicks, MAX_HOLD_TICKS));
        return TaskSnapshot.progress(stage,
                "Interact with block using " + record.button,
                Math.min(interactTicks, required), required,
                record.x, record.y, record.z,
                phase == Phase.DONE ? failReason : null,
                breakStarted ? "vanilla_break_in_progress" : null,
                ((long) interactTicks << 3) ^ (phase == null ? 0L : phase.ordinal()));
    }
    @Override public IntentContract intentContract() {
        return new IntentContract("Interact with the requested block",
                "Vanilla accepts the requested interaction"
                        + ("attack".equals(record.button) ? " and the target becomes air" : ""),
                record.x, record.y, record.z,
                IntentContract.TerrainPolicy.CONSERVATIVE,
                java.util.List.of(new IntentContract.Constraint("target_only",
                        IntentContract.ConstraintKind.HARD,
                        "Navigation may not modify unrelated terrain",
                        "interaction")));
    }
    @Override protected String successMessage() {
        return "Interacted at (" + record.x + ", " + record.y + ", " + record.z
                + ") with button=" + record.button;
    }
    @Override protected String timeoutMessage() {
        return "Interaction timed out at (" + record.x + ", " + record.y + ", " + record.z + ")";
    }
    @Override protected String failureMessage() {
        return failReason != null ? failReason : "Interaction failed at ("
                + record.x + ", " + record.y + ", " + record.z + ")";
    }
}
