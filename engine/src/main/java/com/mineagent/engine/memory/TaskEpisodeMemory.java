package com.mineagent.engine.memory;

import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.world.SemanticWorldModel;
import java.util.*;

/** Bounded task timelines preserve old outcomes; retrieving them never creates world facts. */
public final class TaskEpisodeMemory {
    public enum Status { ACTIVE, INTERRUPTED, VERIFIED }
    public record Milestone(String id, String action, boolean success, String evidence, long tick) {
        public Milestone {
            id = compact(id, 180); action = compact(action, 160); evidence = compact(evidence, 400);
            tick = Math.max(0, tick);
        }
    }
    public record Episode(String id, String goal, String environment, Status status,
                          List<Milestone> milestones, long startedTick, long updatedTick) {
        public Episode {
            id = compact(id, 100); goal = compact(goal, 512); environment = compact(environment, 512);
            status = status == null ? Status.INTERRUPTED : status;
            milestones = milestones == null ? List.of() : milestones.stream().filter(Objects::nonNull).limit(64).toList();
            startedTick = Math.max(0, startedTick); updatedTick = Math.max(startedTick, updatedTick);
        }
    }
    public record State(List<Episode> episodes, String currentId, long sequence) {}
    private final LinkedHashMap<String, Episode> episodes = new LinkedHashMap<>();
    private String currentId;
    private long sequence;

    public synchronized String currentGoal() {
        Episode current = episodes.get(currentId);
        return current == null ? "" : current.goal();
    }

    public synchronized void begin(String goal, String environment, long tick) {
        goal = compact(goal, 512);
        if (goal.isBlank()) return;
        Episode current = episodes.get(currentId);
        if (current != null && current.status() == Status.ACTIVE && current.goal().equals(goal)
                && current.environment().equals(environment)) return;
        if (current != null && current.status() == Status.ACTIVE)
            episodes.put(current.id(), copy(current, Status.INTERRUPTED, current.milestones(), tick));
        do {
            sequence=sequence==Long.MAX_VALUE?1:sequence+1;
            currentId="task-episode-"+sequence;
        } while(episodes.containsKey(currentId));
        episodes.put(currentId, new Episode(currentId, goal, environment, Status.ACTIVE, List.of(), tick, tick));
        trim();
    }

    public synchronized void outcome(String id, String action, boolean success, String evidence, long tick) {
        Episode current = episodes.get(currentId);
        if (current == null || current.status() != Status.ACTIVE || id == null || id.isBlank()) return;
        if (current.milestones().stream().anyMatch(m -> m.id().equals(id))) return;
        var milestones = new ArrayList<>(current.milestones());
        milestones.add(new Milestone(id, action, success, evidence, tick));
        while (milestones.size() > 64) {
            int victim = -1;
            for (int i = 0; i < milestones.size() - 1; i++) if (!milestones.get(i).success()) { victim = i; break; }
            milestones.remove(victim >= 0 ? victim : 8);
        }
        episodes.put(currentId, copy(current, current.status(), milestones, tick));
    }

    public synchronized boolean verify(PlanGraph.State plan, SemanticWorldModel world, long tick) {
        Episode current = episodes.get(currentId);
        if (current == null || current.status() != Status.ACTIVE || plan == null
                || !current.goal().equals(plan.goal()) || plan.goalConditions().isEmpty()
                || plan.nodes().isEmpty() || plan.nodes().stream().anyMatch(n -> n.status() != PlanGraph.NodeStatus.VERIFIED)
                || plan.goalConditions().stream().anyMatch(c -> !c.matches(world, tick)
                    || world.find(c.subject(),c.predicate(),tick).map(f->f.observedTick()<current.startedTick()).orElse(true))) return false;
        episodes.put(currentId, copy(current, Status.VERIFIED, current.milestones(), tick));
        return true;
    }

    /** A compact chronological skeleton plus exact evidence selected without model calls. */
    public synchronized String render(String query, String environment, int characterBudget) {
        int limit = Math.min(8_000, Math.max(0, characterBudget));
        if (query == null || query.isBlank() || limit < 100) return "";
        var ranked = episodes.values().stream().filter(e -> e.environment().equals(environment))
                .map(e -> Map.entry(e, TextSimilarity.score(query, e.goal() + " " + e.milestones().stream().map(Milestone::action).toList())))
                .filter(e -> e.getValue() >= 0.20)
                .sorted(Map.Entry.<Episode, Double>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey().startedTick(), Comparator.reverseOrder()))
                .limit(4).toList();
        StringBuilder out = new StringBuilder();
        for (var entry : ranked) {
            Episode e = entry.getKey();
            String header = "task=" + e.id() + " status=" + e.status() + " goal=" + e.goal() + "\n";
            if (out.length() + header.length() > limit) continue;
            out.append(header);
            for (var milestone : e.milestones()) {
                String line = "  " + milestone.tick() + " " + milestone.action() + " "
                        + (milestone.success() ? "verified_action" : "failed_action") + " " + milestone.evidence() + "\n";
                if (out.length() + line.length() <= limit) out.append(line);
            }
        }
        return out.toString();
    }

    public synchronized State exportState() { return new State(List.copyOf(episodes.values()), currentId, sequence); }
    public synchronized void importState(State state) {
        episodes.clear(); currentId = null; sequence = 0;
        if (state == null) return;
        sequence = Math.max(0, state.sequence());
        if (state.episodes() != null) for (var e : state.episodes()) if (e != null && !e.id().isBlank())
            episodes.put(e.id(), e.status() == Status.ACTIVE ? copy(e, Status.INTERRUPTED, e.milestones(), e.updatedTick()) : e);
        // Resuming an old task needs a new explicit goal; no historical executor ownership is restored.
        trim();
    }
    private void trim() {
        while (episodes.size() > 128) {
            var counts = episodes.values().stream().collect(java.util.stream.Collectors.groupingBy(Episode::goal, java.util.stream.Collectors.counting()));
            Episode victim = episodes.values().stream().filter(e -> !e.id().equals(currentId))
                    .min(Comparator.comparingInt((Episode e) -> counts.get(e.goal()) > 1 ? 0 : 1)
                            .thenComparingInt(e -> e.status() == Status.VERIFIED ? 1 : 0)
                            .thenComparingLong(Episode::updatedTick)).orElseThrow();
            episodes.remove(victim.id());
        }
    }
    private static Episode copy(Episode e, Status status, List<Milestone> milestones, long tick) {
        return new Episode(e.id(), e.goal(), e.environment(), status, milestones, e.startedTick(), tick);
    }
    private static String compact(String text, int limit) {
        if (text == null) return "";
        text = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
