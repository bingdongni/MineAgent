package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.tools.block.AutoMineTool;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Executes a mining task - finds blocks of the specified type within radius,
 * navigates to each one, breaks it, and counts until the target count is reached.
 *
 * <p>Phase lifecycle:
 * <ol>
 *   <li>SCAN - find matching blocks</li>
 *   <li>NAVIGATE - walk to the next block</li>
 *   <li>DIG - break the block</li>
 *   <li>(repeat until count reached)</li>
 * </ol>
 */
public class MineBlockTask extends CompanionTask<AutoMineTool.MineBlockTaskRecord> {

    private enum Phase { SCAN, NAVIGATE, DIG, DONE }

    private PlayerNav nav;
    private Phase phase;
    private int minedCount;
    private List<BlockPos> targetBlocks;
    private int currentBlockIndex;
    private int digTicks;
    private BlockPos activeBreakTarget;
    private String failReason;
    private final Set<BlockPos> unreachableBlocks = new HashSet<>();
    private BlockScanner.ScanSession scanSession;

    private int digTimeoutTicks;

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
                // Rescan without the failed position. Aborting the whole task,
                // or immediately selecting the same block again, both prevent
                // collecting other reachable blocks in the requested radius.
                failReason = "Navigation failed: " + reason;
                beginScan();
            }
        });

        // A radius-32 sphere contains more than 100k cells. Start a cursor
        // here and let tickScan enforce a bounded server-tick work budget.
        beginScan();
    }

    private void beginScan() {
        var pos = TaskContext.serverPlayer(player).blockPosition();
        var level = TaskContext.serverPlayer(player).serverLevel();
        scanSession = BlockScanner.begin(level, pos.getX(), pos.getY(), pos.getZ(),
                record.radius, record.blockType);
        targetBlocks = null;
        currentBlockIndex = 0;
        phase = Phase.SCAN;
    }

    private void finishScan() {
        targetBlocks = scanSession.results();
        scanSession = null;
        targetBlocks = targetBlocks.stream()
                .filter(block -> !unreachableBlocks.contains(block))
                .toList();

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
        // Timeout check
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        switch (phase) {
            case SCAN -> tickScan();
            case NAVIGATE -> tickNavigate();
            case DIG -> tickDig();
            case DONE -> {}
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (minedCount >= record.count) return TaskState.SUCCESS;
        if (phase == Phase.DONE && failReason == null && minedCount > 0) return TaskState.SUCCESS;
        return TaskState.RUNNING;
    }

    private void tickScan() {
        if (scanSession == null) beginScan();
        scanSession.scan(4096);
        if (!scanSession.isComplete()) return;
        finishScan();
        if (phase != Phase.DONE && !targetBlocks.isEmpty()) {
            navigateToNextBlock();
        }
    }

    private void tickNavigate() {
        nav.tick();
    }

    private void tickDig() {
        if (currentBlockIndex >= targetBlocks.size()) {
            // Ran out of known blocks - rescan
            beginScan();
            return;
        }

        BlockPos target = targetBlocks.get(currentBlockIndex);
        var sp = TaskContext.serverPlayer(player);
        var level = sp.serverLevel();

        // An air target counts only when this task actually started breaking
        // it. Disappearance before START may be another player's work and must
        // not inflate the requested mined count.
        if (level.getBlockState(target).isAir()) {
            boolean brokenByThisTask = target.equals(activeBreakTarget);
            activeBreakTarget = null;
            if (brokenByThisTask) minedCount++;
            currentBlockIndex++;
            digTicks = 0;
            if (minedCount >= record.count) {
                cancelNav();
                return;
            }
            if (currentBlockIndex >= targetBlocks.size()) {
                beginScan();
            } else {
                navigateToNextBlock();
            }
            return;
        }

        // START only once and let ServerPlayerGameMode.tick() advance real
        // mining progress. Repeated destroyBlock() calls bypassed hardness and
        // made instantBreak=false ineffective.
        if (activeBreakTarget == null) {
            // Select the best tool before checking durability. Otherwise a
            // fragile held item aborts mining despite a healthy tool in the
            // inventory being available for this exact block.
            digTimeoutTicks = BlockDigger.expectedBreakTicks(sp, target);
            int durability = BlockDigger.toolDurability(sp);
            if (durability != Integer.MAX_VALUE && durability <= 1) {
                failReason = "No safe tool available (held durability: " + durability + ")";
                phase = Phase.DONE;
                return;
            }
            if (BlockDigger.startBreaking(sp, target)) {
                activeBreakTarget = target;
            } else {
                unreachableBlocks.add(target);
                currentBlockIndex++;
                digTicks = 0;
                if (currentBlockIndex >= targetBlocks.size()) {
                    beginScan();
                } else {
                    navigateToNextBlock();
                }
                return;
            }
        }
        digTicks++;

        // FakePlayerGameMode may complete START immediately when configured.
        if (level.getBlockState(target).isAir()) {
            minedCount++;
            activeBreakTarget = null;
            currentBlockIndex++;
            digTicks = 0;
            if (minedCount >= record.count) {
                cancelNav();
            } else if (currentBlockIndex >= targetBlocks.size()) {
                beginScan();
            } else {
                navigateToNextBlock();
            }
        } else if (digTicks > digTimeoutTicks) {
            BlockDigger.abortBreaking(sp, target);
            activeBreakTarget = null;
            unreachableBlocks.add(target);
            currentBlockIndex++;
            digTicks = 0;
            if (currentBlockIndex >= targetBlocks.size()) {
                beginScan();
            } else {
                navigateToNextBlock();
            }
        }
    }

    private void navigateToNextBlock() {
        if (currentBlockIndex >= targetBlocks.size()) {
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
    public void onInterrupt() {
        cancelNav();
    }

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
        if (failReason != null) return failReason;
        return "Mining failed after mining " + minedCount + "/" + record.count
                + " blocks of " + record.blockType;
    }
}
