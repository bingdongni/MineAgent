package com.mineagent.tools;

import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.tools.block.AutoMineTool;
import com.mineagent.tools.block.BuildTool;
import com.mineagent.tools.block.InspectBlockTool;
import com.mineagent.tools.block.InspectBlockStorageTool;
import com.mineagent.tools.combat.MeleeAttackTool;
import com.mineagent.tools.combat.RangedAttackTool;
import com.mineagent.tools.crafting.CraftTool;
import com.mineagent.tools.crafting.LookupRecipeTool;
import com.mineagent.tools.inventory.CollectItemsTool;
import com.mineagent.tools.inventory.DropItemsTool;
import com.mineagent.tools.inventory.EatItemTool;
import com.mineagent.tools.inventory.EquipItemTool;
import com.mineagent.tools.inventory.TransferItemsTool;
import com.mineagent.tools.management.QueryExtraToolsTool;
import com.mineagent.tools.management.CoordinateTeamTool;
import com.mineagent.tools.management.TaskStatusTool;
import com.mineagent.tools.management.TaskStopTool;
import com.mineagent.tools.management.TodowriteTool;
import com.mineagent.tools.movement.MoveToTool;
import com.mineagent.tools.perception.GetSelfStatusTool;
import com.mineagent.tools.perception.LookAroundTool;
import com.mineagent.tools.perception.RecallMemoryTool;
import com.mineagent.tools.perception.ScanBlocksTool;
import com.mineagent.tools.perception.ScanNearbyEntitiesTool;
import com.mineagent.tools.planning.ResolveNeedTool;
import com.mineagent.tools.planning.ExploreMechanismTool;
import com.mineagent.tools.skill.LearnedSkillsTool;
import com.mineagent.tools.skill.LoadSkillTool;
import com.mineagent.tools.skill.ExecuteSkillTool;

/**
 * Registers all MineAgent built-in tools. Called at mod initialization.
 */
public final class ToolRegistration {

    private ToolRegistration() {}

    /** Register all built-in tools. */
    public static void registerAll() {
        // Movement
        ToolRegistry.register(new MoveToTool());

        // Perception
        ToolRegistry.register(new LookAroundTool());
        ToolRegistry.register(new ScanBlocksTool());
        ToolRegistry.register(new GetSelfStatusTool());
        ToolRegistry.register(new ScanNearbyEntitiesTool());
        ToolRegistry.register(new RecallMemoryTool());
        ToolRegistry.register(new ResolveNeedTool());
        ToolRegistry.register(new ExploreMechanismTool());

        // Block
        ToolRegistry.register(new AutoMineTool());
        ToolRegistry.register(new BuildTool());
        ToolRegistry.register(new InspectBlockTool());
        ToolRegistry.register(new InspectBlockStorageTool());

        // Combat
        ToolRegistry.register(new MeleeAttackTool());
        ToolRegistry.register(new RangedAttackTool());

        // Inventory
        ToolRegistry.register(new EquipItemTool());
        ToolRegistry.register(new EatItemTool());
        ToolRegistry.register(new DropItemsTool());
        ToolRegistry.register(new CollectItemsTool());
        ToolRegistry.register(new TransferItemsTool());

        // Crafting
        ToolRegistry.register(new CraftTool());
        ToolRegistry.register(new LookupRecipeTool());

        // Interaction
        ToolRegistry.register(new InteractAtTool());
        ToolRegistry.register(new InteractEntityTool());

        // GUI
        ToolRegistry.register(new InspectGuiTool());
        ToolRegistry.register(new CloseGuiTool());

        // Location
        ToolRegistry.register(new LocateStructureTool());
        ToolRegistry.register(new LocateBiomeTool());

        // Status
        ToolRegistry.register(new GetOwnerStatusTool());
        ToolRegistry.register(new GetWorldInfoTool());

        // Management
        ToolRegistry.register(new TodowriteTool());
        ToolRegistry.register(new TaskStatusTool());
        ToolRegistry.register(new TaskStopTool());
        ToolRegistry.register(new QueryExtraToolsTool());
        ToolRegistry.register(new CoordinateTeamTool());

        // Skills
        ToolRegistry.register(new LoadSkillTool());
        ToolRegistry.register(new LearnedSkillsTool());
        ToolRegistry.register(new ExecuteSkillTool());

        System.out.println("[MineAgent] Registered " + ToolRegistry.size() + " tools");
    }
}
