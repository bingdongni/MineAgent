package com.mineagent.engine.planning;

import com.mineagent.api.task.TaskState;
import com.mineagent.engine.exploration.*;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ExperienceRepairPlannerTest {
    private final SemanticWorldModel world = new SemanticWorldModel();
    private final MechanismExplorer explorer = new MechanismExplorer(world,(a,b,c,d)->{},new SkillLibrary(),
            new EnvironmentFingerprint("1.21.1","fabric","mods","ids"),null);
    private final PlanGraph graph = new PlanGraph();

    @Test void insertsVerifiedExperienceBeforeBlockedStepAndPreservesHistoryAndOtherBranches() {
        learn(); plan(true);
        var before=graph.exportState();
        var suggestion=ExperienceRepairPlanner.suggest(graph,world,explorer.knowledgeBase(),20);
        assertTrue(suggestion.found(),suggestion.message()); assertEquals(1,suggestion.ruleIds().size());
        var update=graph.repairPlan(before.goal(),suggestion.drafts(),before.constraints(),before.goalConditions());
        assertTrue(update.accepted(),update.warnings().toString());
        var after=graph.exportState();
        assertEquals(PlanGraph.NodeStatus.VERIFIED,after.nodes().stream().filter(node->node.id().equals("gather")).findFirst().orElseThrow().status());
        assertTrue(after.nodes().stream().anyMatch(node->node.id().equals("unrelated")));
        var repair=after.nodes().stream().filter(node->node.id().startsWith("experience_repair_")).findFirst().orElseThrow();
        assertTrue(repair.description().contains("execute_skill"));
        assertEquals(List.of(repair.id()),after.nodes().stream().filter(node->node.id().equals("craft")).findFirst().orElseThrow().dependsOn());
        assertEquals(before.goalConditions(),after.goalConditions());
        assertFalse(ExperienceRepairPlanner.suggest(graph,world,explorer.knowledgeBase(),20).found());
    }

    @Test void unavailableConditionsCannotBeCreatedByTheSearch() {
        learn(); plan(false); world.retract("inventory:mod:input","count","server",null,20,false);
        var suggestion=ExperienceRepairPlanner.suggest(graph,world,explorer.knowledgeBase(),20);
        assertFalse(suggestion.found()); assertTrue(suggestion.expanded()<=128);
    }

    @Test void absentExperienceDoesNotInventARepairSkill() {
        observe("0",20); plan(false);
        assertFalse(ExperienceRepairPlanner.suggest(graph,world,explorer.knowledgeBase(),20).found());
    }

    @Test void invalidatedRuleBlocksItsPreviouslyInsertedRepair() {
        learn(); plan(false);
        var suggestion=ExperienceRepairPlanner.suggest(graph,world,explorer.knowledgeBase(),20);
        assertTrue(suggestion.found());
        var state=graph.exportState(); graph.repairPlan(state.goal(),suggestion.drafts(),state.constraints(),state.goalConditions());
        String ruleId=suggestion.ruleIds().getFirst();
        world.observe("mechanism_rule:"+ruleId,"reusable","false",null,1,"server",null,21,0,false);
        assertNotNull(graph.actionBlocker(world,21));
    }

    @Test void repairComposesObservedStageChangesWhileRetainingTypedIdentityGuards() {
        String target = "block:minecraft:overworld:2,64,0";
        for (int before = 0; before < 2; before++) for (int repeat = 0; repeat < 2; repeat++) {
            long tick = 2 + before * 30_000 + repeat * 3L;
            String subject = "block:minecraft:overworld:" + (repeat + 1) + ",64,0";
            world.observe(subject, "block_id", "mod:machine", null, 1, "server", null, tick, 1000, false);
            world.observe(subject, "dimension", "minecraft:overworld", null, 1, "server", null, tick, 1000, false);
            world.observe(subject, "stage", Integer.toString(before), null, 1, "server", null, tick, 1000, false);
            String args = "{\"x\":" + (repeat + 1) + ",\"y\":64,\"z\":0}";
            var proposal = explorer.propose("mod:machine", "advances this observed stage", "interact_at", args,
                    subject, "stage", Integer.toString(before + 1), MechanismExplorer.Risk.MEDIUM,
                    "fixture", true, "close_gui", "{}", 4, 0.5, tick);
            assertTrue(proposal.accepted(), proposal.message());
            String call = "stage-" + tick; explorer.onToolDispatched(call, "interact_at", args, tick);
            world.observe(subject, "stage", Integer.toString(before + 1), null, 1, "server", call, tick + 1, 1000, false);
            explorer.onToolResult(call, "interact_at", true, false, "{\"success\":true}", tick + 1);
        }
        world.observe(target, "stage", "0", null, 1, "server", null, 30_020, 1000, false);
        var goal = new PlanGraph.GoalCondition(target, "stage", "2", PlanGraph.Comparison.EQUALS, 0.7);
        graph.replacePlan("finish machine", List.of(new PlanGraph.DraftNode("finish", "finish", "observed", "medium",
                List.of(), PlanGraph.NodeStatus.PENDING, List.of(goal))), List.of(), List.of(goal));
        var suggestion = ExperienceRepairPlanner.suggest(graph, world, explorer.knowledgeBase(), 30_020);
        assertTrue(suggestion.found(), suggestion.message()); assertEquals(2, suggestion.ruleIds().size());
        assertEquals(List.of("experience_repair_1"), suggestion.drafts().get(1).dependsOn());
        assertEquals(List.of("experience_repair_2"), suggestion.drafts().getLast().dependsOn());
    }

    private void learn() {
        for(int i=0;i<2;i++) {
            long tick=2+i*4; observe("0",tick);
            String args="{\"x\":"+i+",\"y\":64,\"z\":0}";
            var proposal=explorer.propose("mod:processor","makes three inputs","interact_at",args,"inventory:mod:input","count","3",
                    MechanismExplorer.Risk.MEDIUM,"fixture",true,"close_gui","{}",4,0.5,tick);
            assertTrue(proposal.accepted(),proposal.message());
            explorer.onToolDispatched("source-"+i,"interact_at",args,tick);
            observe("3",tick+1); explorer.onToolResult("source-"+i,"interact_at",true,false,"{\"success\":true}",tick+1);
        }
        observe("0",20);
    }
    private void plan(boolean branch) {
        var material=new PlanGraph.GoalCondition("inventory:mod:input","count","3",PlanGraph.Comparison.AT_LEAST,0.7);
        var nodes=new java.util.ArrayList<PlanGraph.DraftNode>();
        nodes.add(new PlanGraph.DraftNode("gather","gather","observed","medium",List.of(),PlanGraph.NodeStatus.PENDING));
        nodes.add(new PlanGraph.DraftNode("craft","craft","output observed","medium",List.of("gather"),PlanGraph.NodeStatus.PENDING,List.of(material)));
        if(branch) nodes.add(new PlanGraph.DraftNode("unrelated","unrelated","observed","low",List.of(),PlanGraph.NodeStatus.PENDING));
        graph.replacePlan("produce output",nodes,List.of(),List.of(new PlanGraph.GoalCondition("inventory:mod:output","count","1",PlanGraph.Comparison.AT_LEAST,0.7)));
        graph.refreshReadiness(world,20); graph.bindTask("gather-task","collect",null,20);
        graph.recordOutcome("gather-task",TaskState.SUCCESS,null,"verified",20);
    }
    private void observe(String value,long tick) { world.observe("inventory:mod:input","count",value,null,1,"server",null,tick,1000,false); }
}
