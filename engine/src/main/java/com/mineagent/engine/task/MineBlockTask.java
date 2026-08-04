package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.tools.block.AutoMineTool;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds reachable matching blocks and mines them through vanilla progress. */
public class MineBlockTask extends CompanionTask<AutoMineTool.MineBlockTaskRecord> {

    private enum Phase { SCAN, NAVIGATE, DIG, DONE }

    private PlayerNav nav;
    private Phase phase;
    private int minedCount;
    private List<BlockPos> targetBlocks;
    private int currentBlockIndex;
    private int digTicks;
    private int digTimeoutTicks;
    private BlockPos activeBreakTarget;
    private String failReason;
    private final Set<BlockPos> unreachableBlocks = new HashSet<>();
    private BlockScanner.ScanSession scanSession;

    public MineBlockTask(AgentPlayer player, AutoMineTool.MineBlockTaskRecord record) {
        super(player, record);
    }

    @Override
    public void onStart() {
        phase = Phase.SCAN;
        minedCount = 0;
        currentBlockIndex = 0;
        digTicks = 0;
        digTimeoutTicks = Integer.MAX_VALUE;
        activeBreakTarget = null;
        failReason = null;
        unreachableBlocks.clear();

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches);
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.DIG;
            }

            @Override
            public void onNavigationFailed(String reason) {
                if (targetBlocks != null && currentBlockIndex < targetBlocks.size()) {
                    unreachableBlocks.add(targetBlocks.get(currentBlockIndex));
                }
                failReason = "Navigation failed: " + reason;
                beginScan();
            }
        });
        beginScan();
    }

    private void beginScan() {
        var sp = TaskContext.serverPlayer(player);
        var pos = sp.blockPosition();
        scanSession = BlockScanner.begin(sp.serverLevel(), pos.getX(), pos.getY(), pos.getZ(),
                record.radius, record.blockType);
        targetBlocks = null;
        currentBlockIndex = 0;
        phase = Phase.SCAN;
    }

    private void finishScan() {
        targetBlocks = scanSession.results().stream()
                .filter(block -> !unreachableBlocks.contains(block))
                .toList();
        scanSession = null;
        if (targetBlocks.isEmpty()) {
            if (minedCount > 0) {
                failReason = null;
            } else if (failReason == null) {
                failReason = "No reachable blocks of type '" + record.blockType
                        + "' found within radius " + record.radius;
            }
            phase = Phase.DONE;
            return;
        }
        currentBlockIndex = 0;
    }

    @Override
    public TaskState onTick() {
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (record.deadline() > 0L && gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        switch (phase) {
            case SCAN -> tickScan();
            case NAVIGATE -> nav.tick();
            case DIG -> tickDig();
            case DONE -> { }
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (minedCount >= record.count) return TaskState.SUCCESS;
        if (phase == Phase.DONE && minedCount > 0) return TaskState.SUCCESS;
        return TaskState.RUNNING;
    }

    private void tickScan() {
        if (scanSession == null) beginScan();
        // Bound the radius scan so a large request cannot monopolize a tick.
        scanSession.scan(4096);
        if (!scanSession.isComplete()) return;
        finishScan();
        if (phase != Phase.DONE) navigateToNextBlock();
    }

    private void tickDig() {
        if (targetBlocks == null || currentBlockIndex >= targetBlocks.size()) {
            beginScan();
            return;
        }

        BlockPos target = targetBlocks.get(currentBlockIndex);
        var sp = TaskContext.serverPlayer(player);
        var level = sp.serverLevel();

        if (level.getBlockState(target).isAir()) {
            // Only count disappearance after this task owned the break target.
            if (target.equals(activeBreakTarget)) minedCount++;
            activeBreakTarget = null;
            advanceTarget();
            return;
        }

        if (activeBreakTarget == null) {
            digTimeoutTicks = BlockDigger.expectedBreakTicks(sp, target);
            int durability = BlockDigger.toolDurability(sp);
            if (durability != Integer.MAX_VALUE && durability <= 1) {
                failReason = "No safe tool available (held durability: " + durability + ")";
                phase = Phase.DONE;
                return;
            }
            if (!BlockDigger.startBreaking(sp, target)) {
                unreachableBlocks.add(target);
                advanceTarget();
                return;
            }
            activeBreakTarget = target;
        }

        digTicks++;
        if (level.getBlockState(target).isAir()) {
            minedCount++;
            activeBreakTarget = null;
            advanceTarget();
        } else if (digTicks > digTimeoutTicks) {
            BlockDigger.abortBreaking(sp, target);
            activeBreakTarget = null;
            unreachableBlocks.add(target);
            advanceTarget();
        }
    }

    private void advanceTarget() {
        currentBlockIndex++;
        digTicks = 0;
        if (minedCount >= record.count) {
            cancelNav();
        } else if (targetBlocks == null || currentBlockIndex >= targetBlocks.size()) {
            beginScan();
        } else {
            navigateToNextBlock();
        }
    }

    private void navigateToNextBlock() {
        if (targetBlocks == null || currentBlockIndex >= targetBlocks.size()) {
            beginScan();
            return;
        }
        BlockPos target = targetBlocks.get(currentBlockIndex);
        nav.navigateToBlock(target.getX(), target.getY(), target.getZ());
        phase = Phase.NAVIGATE;
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        if (activeBreakTarget != null) {
            BlockDigger.abortBreaking(TaskContext.serverPlayer(player), activeBreakTarget);
            activeBreakTarget = null;
        }
        TaskContext.inputDriver(player).clear();
    }

    @Override
    public void onInterrupt() { cancelNav(); }

    @Override
    protected String successMessage() {
        return "Mined " + minedCount + " blocks of " + record.blockType;
    }

    @Override
    protected String timeoutMessage() {
        return "Mining timed out after mining " + minedCount + "/" + record.count
                + " blocks of " + record.blockType;
    }

    @Override
    protected String failureMessage() {
        return failReason != null ? failReason
                : "Mining failed after mining " + minedCount + "/" + record.count
                + " blocks of " + record.blockType;
    }
}
