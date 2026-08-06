package com.mineagent.engine.exploration;

import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MechanismExplorerTest {
    @Test void supportsHypothesisOnlyAfterDeclaredPostcondition() {
        SemanticWorldModel world = new SemanticWorldModel();
        List<String> evidence = new ArrayList<>();
        MechanismExplorer explorer = new MechanismExplorer(world,
                (experiment, supported, detail, tick) ->
                        evidence.add(supported + ":" + detail));
        var proposal = explorer.propose("mod:machine", "use opens a menu",
                "interact_at", "{\"x\":1}", "tool:interact_at",
                "outcome", "success", MechanismExplorer.Risk.MEDIUM, 10L);
        assertTrue(proposal.accepted());
        explorer.onToolDispatched("call", "interact_at", "{\"x\":1}", 11L);
        world.recordOutcome("interact_at", true, "opened", "call", 12L);
        explorer.onToolResult("call", true, false, "opened", 12L);

        assertEquals(MechanismExplorer.Status.SUPPORTED,
                explorer.get(proposal.experiment().id()).status());
        assertEquals(1, evidence.size());
    }

    @Test void rejectsHighRiskOrDestructiveProbe() {
        MechanismExplorer explorer = new MechanismExplorer(
                new SemanticWorldModel(), (a, b, c, d) -> {});
        assertFalse(explorer.propose("x", "break it", "auto_mine", "{}",
                null, null, null, MechanismExplorer.Risk.LOW, 1L).accepted());
        assertFalse(explorer.propose("x", "touch it", "interact_at", "{}",
                null, null, null, MechanismExplorer.Risk.HIGH, 1L).accepted());
    }

    @Test void newContradictoryObservationRefutesHypothesis() {
        SemanticWorldModel world = new SemanticWorldModel();
        MechanismExplorer explorer = new MechanismExplorer(world, (a, b, c, d) -> {});
        var proposal = explorer.propose("mod:machine", "use makes it active",
                "interact_at", "{}", "machine:mod:machine", "state", "active",
                MechanismExplorer.Risk.MEDIUM, 1L);
        explorer.onToolDispatched("call", "interact_at", "{}", 2L);
        world.observe("machine:mod:machine", "state", "jammed", null,
                0.9, "inspect_gui", "call", 3L, 40L, false);
        explorer.onToolResult("call", true, false, "interaction accepted", 3L);

        assertEquals(MechanismExplorer.Status.REFUTED,
                explorer.get(proposal.experiment().id()).status());
    }

    @Test void restartTurnsAnInFlightExperimentIntoInconclusiveEvidence() {
        MechanismExplorer source = new MechanismExplorer(
                new SemanticWorldModel(), (a, b, c, d) -> {});
        var proposal = source.propose("mod:block", "use opens it", "interact_at",
                "{}", null, null, null, MechanismExplorer.Risk.MEDIUM, 1L);
        MechanismExplorer restored = new MechanismExplorer(
                new SemanticWorldModel(), (a, b, c, d) -> {});
        restored.importState(source.exportState());
        assertEquals(MechanismExplorer.Status.INCONCLUSIVE,
                restored.get(proposal.experiment().id()).status());
    }

    @Test void failedProbeIsInconclusiveRatherThanNegativeRuleEvidence() {
        List<String> evidence = new ArrayList<>();
        MechanismExplorer explorer = new MechanismExplorer(new SemanticWorldModel(),
                (experiment, supported, detail, tick) -> evidence.add(detail));
        explorer.propose("mod:block", "use opens it", "interact_at", "{}",
                null, null, null, MechanismExplorer.Risk.MEDIUM, 1L);
        explorer.onToolDispatched("call", "interact_at", "{}", 2L);
        explorer.onToolResult("call", false, false, "out of reach", 3L);
        assertTrue(evidence.isEmpty());
    }
}
