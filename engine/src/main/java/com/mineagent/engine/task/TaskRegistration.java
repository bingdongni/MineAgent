package com.mineagent.engine.task;

import com.mineagent.api.task.CompanionTaskFactory;
import com.mineagent.tools.InteractAtTool;
import com.mineagent.tools.InteractEntityTool;
import com.mineagent.tools.LocateBiomeTool;
import com.mineagent.tools.LocateStructureTool;
import com.mineagent.tools.block.AutoMineTool;
import com.mineagent.tools.block.BuildTool;
import com.mineagent.tools.combat.MeleeAttackTool;
import com.mineagent.tools.combat.RangedAttackTool;
import com.mineagent.tools.inventory.CollectItemsTool;
import com.mineagent.tools.movement.MoveToTool;

/**
 * Registers all CompanionTask executor types in the CompanionTaskFactory.
 * Called once during engine initialization to wire up every TaskRecord
 * type to its corresponding executor.
 *
 * <p>After calling {@link #registerAll()}, the factory will be able to
 * create a CompanionTask for any TaskRecord dispatched by the tools.
 */
public final class TaskRegistration {

    private TaskRegistration() {}

    /**
     * Register all task types. Must be called once during engine startup.
     */
    public static void registerAll() {
        // Movement
        CompanionTaskFactory.register(
                MoveToTool.MoveToTaskRecord.class,
                MoveToTask::new
        );

        // Mining
        CompanionTaskFactory.register(
                AutoMineTool.MineBlockTaskRecord.class,
                MineBlockTask::new
        );

        // Building
        CompanionTaskFactory.register(
                BuildTool.BuildTaskRecord.class,
                BuildTask::new
        );

        // Melee combat
        CompanionTaskFactory.register(
                MeleeAttackTool.MeleeAttackTaskRecord.class,
                MeleeAttackTask::new
        );

        // Ranged combat
        CompanionTaskFactory.register(
                RangedAttackTool.RangedAttackTaskRecord.class,
                RangedAttackTask::new
        );

        // Item collection
        CompanionTaskFactory.register(
                CollectItemsTool.CollectItemsTaskRecord.class,
                CollectItemsTask::new
        );

        // Block interaction
        CompanionTaskFactory.register(
                InteractAtTool.InteractAtTaskRecord.class,
                InteractAtTask::new
        );

        // Entity interaction
        CompanionTaskFactory.register(
                InteractEntityTool.InteractEntityTaskRecord.class,
                InteractEntityTask::new
        );

        // Structure location
        CompanionTaskFactory.register(
                LocateStructureTool.LocateStructureTaskRecord.class,
                LocateStructureTask::new
        );

        // Biome location
        CompanionTaskFactory.register(
                LocateBiomeTool.LocateBiomeTaskRecord.class,
                LocateBiomeTask::new
        );
    }
}
