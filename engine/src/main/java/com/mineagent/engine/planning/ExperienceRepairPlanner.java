package com.mineagent.engine.planning;

import com.mineagent.engine.exploration.MechanismKnowledgeBase;
import com.mineagent.engine.world.SemanticWorldModel;
import java.util.*;

/** Bounded backward repair from verified experience; execution still checks every live condition. */
public final class ExperienceRepairPlanner {
    public record Suggestion(boolean found, List<String> ruleIds, List<PlanGraph.DraftNode> drafts,
                             int expanded, String message) {
        public Suggestion { ruleIds = List.copyOf(ruleIds); drafts = List.copyOf(drafts); }
    }
    private record Search(Map<String, String> facts, List<MechanismKnowledgeBase.Rule> path) {}
    private ExperienceRepairPlanner() {}

    public static Suggestion suggest(PlanGraph plan, SemanticWorldModel world,
                                      MechanismKnowledgeBase knowledge, long tick) {
        plan.refreshReadiness(world, tick);
        var gap = plan.prerequisiteGap();
        if (gap == null) return new Suggestion(false, List.of(), List.of(), 0, "No blocked prerequisite requires repair");
        var available = knowledge.rules().stream()
                .filter(rule -> rule.reusable(knowledge.currentFingerprint()) && rule.adapterSkill() != null)
                .filter(rule -> !rule.expectedValue().equals("*"))
                .sorted(Comparator.comparing(MechanismKnowledgeBase.Rule::id)).toList();
        List<MechanismKnowledgeBase.Rule> operators = relevantOperators(available,gap.condition());
        Map<String, String> initial = new TreeMap<>();
        List<PlanGraph.GoalCondition> predicates = new ArrayList<>();
        predicates.add(gap.condition());
        for (var rule : operators) predicates.addAll(rule.prerequisites());
        for (var condition : predicates) world.find(condition.subject(), condition.predicate(), tick)
                .filter(fact -> fact.confidenceAt(tick) >= condition.minimumConfidence())
                .ifPresent(fact -> initial.put(key(condition.subject(), condition.predicate()), fact.value()));
        ArrayDeque<Search> queue = new ArrayDeque<>();
        queue.add(new Search(initial, List.of()));
        Set<Map<String, String>> visited = new HashSet<>();
        visited.add(Map.copyOf(initial));
        int expanded = 0;
        while (!queue.isEmpty() && expanded < 128) {
            Search state = queue.remove(); expanded++;
            if (matches(gap.condition(), state.facts())) return drafts(plan, gap, state.path(), expanded);
            if (state.path().size() >= 8) continue;
            for (var rule : operators) {
                if (!rule.prerequisites().stream().allMatch(condition -> matches(condition, state.facts()))) continue;
                Map<String, String> next = new TreeMap<>(state.facts());
                // Only the declared effect is modeled. Forget potentially consumed inputs;
                // an unknown side effect cannot manufacture reusable inventory or power.
                for (var condition : rule.prerequisites()) if (!Set.of("block_id", "entity_type", "dimension", "capability_face").contains(condition.predicate()))
                    next.remove(key(condition.subject(), condition.predicate()));
                next.put(key(rule.expectedSubject(), rule.expectedPredicate()), rule.expectedValue());
                if (visited.size() >= 256 || !visited.add(Map.copyOf(next))) continue;
                List<MechanismKnowledgeBase.Rule> path = new ArrayList<>(state.path());
                path.add(rule); queue.add(new Search(next, List.copyOf(path)));
            }
        }
        return new Suggestion(false, List.of(), List.of(), expanded,
                "No verified bounded repair found. Observe missing facts or learn a relevant operator; do not infer unavailable capabilities.");
    }

    /** Backward relevance precedes the operator cap, so unrelated old tasks cannot hide the needed operator. */
    public static List<MechanismKnowledgeBase.Rule> relevantOperators(Collection<MechanismKnowledgeBase.Rule> available,
                                                                      PlanGraph.GoalCondition goal) {
        var needed=new LinkedHashSet<PlanGraph.GoalCondition>();needed.add(goal);
        var selected=new LinkedHashMap<String,MechanismKnowledgeBase.Rule>();
        for(int depth=0;depth<8&&selected.size()<64;depth++) {
            int before=selected.size();
            for(var rule:available) {
                if(selected.containsKey(rule.id())||rule.expectedValue().equals("*"))continue;
                String effect=key(rule.expectedSubject(),rule.expectedPredicate());
                if(needed.stream().noneMatch(c->key(c.subject(),c.predicate()).equals(effect)
                        &&matches(c,Map.of(effect,rule.expectedValue()))))continue;
                selected.put(rule.id(),rule);
                for(var prerequisite:rule.prerequisites())if(needed.size()<128)needed.add(prerequisite);
                if(selected.size()==64)break;
            }
            if(selected.size()==before)break;
        }
        return List.copyOf(selected.values());
    }

    private static Suggestion drafts(PlanGraph plan, PlanGraph.PrerequisiteGap gap,
                                     List<MechanismKnowledgeBase.Rule> path, int expanded) {
        var state = plan.exportState();
        var blocked = state.nodes().stream().filter(node -> node.id().equals(gap.nodeId())).findFirst().orElseThrow();
        Set<String> used = new HashSet<>(); state.nodes().forEach(node -> used.add(node.id()));
        List<String> dependencies = blocked.dependsOn();
        List<PlanGraph.DraftNode> nodes = new ArrayList<>();
        int index = 0;
        for (var rule : path) {
            String id;
            do { id = "experience_repair_" + (++index); } while (used.contains(id));
            used.add(id);
            var prerequisites = new ArrayList<>(rule.prerequisites());
            prerequisites.add(new PlanGraph.GoalCondition("mechanism_rule:" + rule.id(), "reusable", "true",
                    PlanGraph.Comparison.EQUALS, 0.99));
            nodes.add(new PlanGraph.DraftNode(id, "execute_skill " + rule.adapterSkill(),
                    rule.expectedSubject() + " " + rule.expectedPredicate() + "=" + rule.expectedValue(),
                    blocked.priority(), dependencies, PlanGraph.NodeStatus.PENDING, prerequisites));
            dependencies = List.of(id);
        }
        for (var node : state.nodes()) if (node.status() != PlanGraph.NodeStatus.VERIFIED) {
            nodes.add(new PlanGraph.DraftNode(node.id(), node.description(), node.successCriterion(), node.priority(),
                    node.id().equals(blocked.id()) ? dependencies : node.dependsOn(), PlanGraph.NodeStatus.PENDING,
                    node.prerequisites()));
        }
        if (nodes.size() + state.nodes().stream().filter(node -> node.status() == PlanGraph.NodeStatus.VERIFIED).count() > 64)
            return new Suggestion(false, List.of(), List.of(), expanded, "Repair would exceed the plan node budget");
        return new Suggestion(true, path.stream().map(MechanismKnowledgeBase.Rule::id).toList(), nodes,
                expanded, "Preserves verified history and unrelated pending branches; every skill still requires live admission and effect verification.");
    }

    private static boolean matches(PlanGraph.GoalCondition condition, Map<String, String> facts) {
        String value = facts.get(key(condition.subject(), condition.predicate()));
        if (value == null) return false;
        if (condition.comparison() == PlanGraph.Comparison.PRESENT) return true;
        if (condition.comparison() == PlanGraph.Comparison.EQUALS)
            return condition.value().equals("*") || condition.value().equalsIgnoreCase(value);
        try {
            double actual = Double.parseDouble(value), expected = Double.parseDouble(condition.value());
            return Double.isFinite(actual) && Double.isFinite(expected)
                    && (condition.comparison() == PlanGraph.Comparison.AT_LEAST ? actual >= expected : actual <= expected);
        } catch (NumberFormatException invalid) { return false; }
    }

    private static String key(String subject, String predicate) { return subject.toLowerCase(Locale.ROOT) + "\0" + predicate.toLowerCase(Locale.ROOT); }
}
