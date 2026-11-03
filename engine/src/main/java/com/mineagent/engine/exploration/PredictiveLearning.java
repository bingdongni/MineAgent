package com.mineagent.engine.exploration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Small, local predictive model of verified interventions, not model-written lessons.
 * Spatial arguments are abstracted only inside an explicitly identified mechanism.
 * Conditions, non-spatial arguments and environment fingerprints remain separate.
 * Predictions advise the planner; they never grant permission to execute an action.
 */
public final class PredictiveLearning {
    private static final int MAX_TRIALS = 256;
    private static final int MAX_PREDICTION_CONTEXTS = 16;
    private static final int MAX_ARGUMENT_CHARS = 2_048;

    public record Trial(String evidenceId, String mechanism, String tool,
                        String arguments, String predicate, String before,
                        String outcome, String context, String environment,
                        long tick, double loss) {}

    public record State(List<Trial> trials) {}

    public record PlanStep(String tool, String arguments, String before,
                           String after, double probability, int independentContexts) {}

    public record Plan(boolean found, List<PlanStep> steps, int expandedStates, String message) {
        public Plan { steps = List.copyOf(steps); }
    }

    public record Prediction(int independentContexts, Map<String, Double> outcomes,
                             double unseenProbability, double uncertaintyBits, double informationGainBits,
                             double learningProgress, boolean needsRevalidation) {
        public Prediction {
            outcomes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
        }
    }

    private final LinkedHashMap<String, Trial> trials = new LinkedHashMap<>();

    /** Repeated callbacks and renaming the same physical setup cannot create support. */
    public synchronized boolean observe(String evidenceId, String mechanism, String tool,
                                        String arguments, String predicate, String before,
                                        String outcome, String physicalContext,
                                        String environment, long tick) {
        if (!valid(evidenceId, 160) || !valid(mechanism, 256) || !valid(tool, 80)
                || !valid(predicate, 128) || !valid(outcome, 256)
                || !valid(physicalContext, 2_048) || !valid(environment, 512)
                || before == null || before.length() > 256 || tick < 0) return false;
        String normalizedArguments;
        try { normalizedArguments = abstractArguments(arguments); }
        catch (IllegalArgumentException invalid) { return false; }
        if (trials.values().stream().anyMatch(trial -> trial.evidenceId().equals(evidenceId))) {
            return false;
        }
        String condition = condition(mechanism, tool, normalizedArguments, predicate, before, environment);
        String contextKey = condition + "|" + digest(physicalContext);
        Trial previous = trials.get(contextKey);
        if (previous != null && tick <= previous.tick()) return false;
        if (previous != null && previous.outcome().equals(outcome)) return false;
        Prediction prior = predict(mechanism, tool, arguments, predicate, before, environment);
        double likelihood = prior.outcomes().getOrDefault(outcome, prior.unseenProbability());
        double loss = -Math.log(Math.max(1e-6, likelihood));
        trials.remove(contextKey);
        trials.put(contextKey, new Trial(evidenceId, mechanism, tool,
                normalizedArguments, predicate, before, outcome, digest(physicalContext),
                environment, tick, loss));
        while (trials.size() > MAX_TRIALS) trials.remove(trials.keySet().iterator().next());
        return true;
    }

    /** Dirichlet posterior predictive distribution, including probability of unseen outcomes. */
    public synchronized Prediction predict(String mechanism, String tool, String arguments,
                                            String predicate, String before, String environment) {
        if (!valid(mechanism, 256) || !valid(tool, 80) || !valid(predicate, 128)
                || before == null || before.length() > 256 || !valid(environment, 512)) {
            throw new IllegalArgumentException("Invalid prediction scope or condition");
        }
        String normalized = abstractArguments(arguments);
        List<Trial> history = trials.values().stream()
                .filter(trial -> trial.mechanism().equals(mechanism) && trial.tool().equals(tool)
                        && trial.arguments().equals(normalized) && trial.predicate().equals(predicate)
                        && trial.before().equals(before) && trial.environment().equals(environment))
                .sorted(Comparator.comparingLong(Trial::tick))
                .toList();
        List<Trial> matching = history.subList(Math.max(0, history.size() - MAX_PREDICTION_CONTEXTS), history.size());
        TreeMap<String, Integer> counts = new TreeMap<>();
        matching.forEach(trial -> counts.merge(trial.outcome(), 1, Integer::sum));
        // No observed outcome alphabet exists yet. Do not invent a binary
        // information-gain estimate; all mass is in the unresolved category.
        if (counts.isEmpty()) return new Prediction(0, Map.of(), 1.0, 0.0,
                0.0, 0.0, true);
        List<String> labels = new ArrayList<>(counts.keySet());
        double[] alpha = new double[labels.size() + 1];
        double total = 0;
        for (int i = 0; i < labels.size(); i++) {
            alpha[i] = counts.getOrDefault(labels.get(i), 0) + 0.5;
            total += alpha[i];
        }
        alpha[labels.size()] = 0.5;
        total += 0.5;
        LinkedHashMap<String, Double> probabilities = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) probabilities.put(labels.get(i), alpha[i] / total);
        double entropy = entropy(alpha, total);
        double posteriorEntropy = 0;
        for (int i = 0; i < alpha.length; i++) {
            double probability = alpha[i] / total;
            alpha[i]++;
            posteriorEntropy += probability * entropy(alpha, total + 1);
            alpha[i]--;
        }
        double progress = progress(matching);
        // A recent outcome different from the dominant established outcome is a drift signal.
        Trial latest = matching.stream().max(Comparator.comparingLong(Trial::tick)).orElseThrow();
        int majority = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        boolean drift = matching.size() >= 2
                && counts.getOrDefault(latest.outcome(), 0) < majority;
        return new Prediction(matching.size(), probabilities, alpha[labels.size()] / total, entropy,
                Math.max(0, entropy - posteriorEntropy), progress,
                matching.size() < 2 || counts.size() > 1 || drift);
    }

    /** Use empirical uncertainty once data exists; a caller estimate remains a cold-start prior. */
    public synchronized double explorationValue(String mechanism, String tool, String arguments,
                                                String environment, double coldStartEstimate) {
        String normalized = abstractArguments(arguments);
        List<Trial> matching = trials.values().stream()
                .filter(trial -> trial.mechanism().equals(mechanism)
                        && trial.tool().equals(tool) && trial.arguments().equals(normalized)
                        && trial.environment().equals(environment)).toList();
        if (matching.isEmpty()) return finiteUnit(coldStartEstimate);
        Trial latest = matching.stream().max(Comparator.comparingLong(Trial::tick)).orElseThrow();
        Prediction prediction = predict(mechanism, tool, arguments,
                latest.predicate(), latest.before(), environment);
        return explorationValue(prediction);
    }

    static double explorationValue(Prediction prediction) {
        // Persistent noise without improving predictions loses priority; useful learning does not.
        return finiteUnit(prediction.informationGainBits() * 4.0
                * (0.25 + Math.min(1.0, prediction.learningProgress()))
                + (prediction.needsRevalidation() ? 0.1 : 0));
    }

    public synchronized int size() { return trials.size(); }

    /** Start a revalidation window for one falsified contract; raw experiment audit remains in its owner. */
    synchronized void startRevalidation(String mechanism,String tool,String arguments,String predicate,String before,String environment) {
        String normalized=abstractArguments(arguments);
        trials.values().removeIf(trial->trial.mechanism().equals(mechanism) && trial.tool().equals(tool)
                && trial.arguments().equals(normalized) && trial.predicate().equals(predicate)
                && trial.before().equals(before) && trial.environment().equals(environment));
    }

    /** Compose verified transitions into a new bounded route; no body action is dispatched. */
    public synchronized Plan plan(String mechanism, String predicate, String before,
                                   String goal, String environment, int maxSteps) {
        if (!valid(mechanism, 256) || !valid(predicate, 128) || before == null
                || before.length() > 256 || !valid(goal, 256) || !valid(environment, 512)
                || "*".equals(goal)) throw new IllegalArgumentException("A plan requires a concrete mechanism, condition and goal");
        if ("<absent>".equals(before)) return new Plan(false, List.of(), 0,
                "Observe the mechanism state first; missing observations are not an initial world state");
        if (before.equals(goal)) return new Plan(true, List.of(), 0,
                "The supplied state already matches the goal; verify it against live evidence");
        LinkedHashMap<String, Trial> conditions = new LinkedHashMap<>();
        for (Trial trial : trials.values()) {
            if (trial.mechanism().equals(mechanism) && trial.predicate().equals(predicate)
                    && trial.environment().equals(environment) && !"<absent>".equals(trial.before())) {
                conditions.put(condition(mechanism, trial.tool(), trial.arguments(), predicate,
                        trial.before(), environment), trial);
            }
        }
        List<PlanStep> edges = new ArrayList<>();
        for (Trial trial : conditions.values()) {
            Prediction prediction = predict(mechanism, trial.tool(), trial.arguments(), predicate,
                    trial.before(), environment);
            if (prediction.independentContexts() < 2 || prediction.needsRevalidation()) continue;
            prediction.outcomes().forEach((outcome, probability) -> {
                if (probability >= 0.7 && !outcome.equals(trial.before())) {
                    edges.add(new PlanStep(trial.tool(), trial.arguments(), trial.before(), outcome,
                            probability, prediction.independentContexts()));
                }
            });
        }
        edges.sort(Comparator.comparing(PlanStep::tool).thenComparing(PlanStep::arguments)
                .thenComparing(PlanStep::after));
        record Route(String state, List<PlanStep> steps, double cost) {}
        record Visit(String state, int depth) {}
        var queue = new java.util.PriorityQueue<Route>(Comparator.comparingDouble(Route::cost)
                .thenComparing(Route::state));
        Map<Visit, Double> bestCosts = new java.util.HashMap<>();
        queue.add(new Route(before, List.of(), 0));
        bestCosts.put(new Visit(before, 0), 0.0);
        int expanded = 0;
        int depthLimit = Math.max(0, Math.min(6, maxSteps));
        while (!queue.isEmpty() && expanded < 64) {
            Route route = queue.remove();
            if (route.cost() > bestCosts.getOrDefault(new Visit(route.state(), route.steps().size()), Double.POSITIVE_INFINITY)) continue;
            if (route.state().equals(goal)) return new Plan(true, route.steps(), expanded,
                    "Proposed route from verified transitions. Bind live targets and verify every step; repair on contradiction");
            expanded++;
            if (route.steps().size() >= depthLimit) continue;
            for (PlanStep edge : edges) {
                if (!edge.before().equals(route.state())) continue;
                double cost = route.cost() + 1.0 - Math.log(edge.probability());
                Visit next = new Visit(edge.after(), route.steps().size() + 1);
                if (cost >= bestCosts.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                List<PlanStep> steps = new ArrayList<>(route.steps());
                steps.add(edge);
                bestCosts.put(next, cost);
                queue.add(new Route(edge.after(), List.copyOf(steps), cost));
            }
        }
        return new Plan(false, List.of(), expanded,
                "No verified route within the planning budget; observe the missing transition or propose a bounded experiment");
    }

    /** Caller-written context labels are annotations, not independent experimental evidence. */
    public static String physicalContext(MechanismExplorer.Experiment experiment) {
        String arguments;
        try { arguments = canonical(JsonParser.parseString(experiment.probeArguments()), 0).toString(); }
        catch (RuntimeException invalidLegacyArguments) { arguments = experiment.probeArguments(); }
        return digestParts(experiment.expectedSubject(),
                arguments,
                experiment.baseline().present() ? experiment.baseline().value() : "<absent>");
    }

    static boolean supportsArguments(String arguments) {
        try { abstractArguments(arguments); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public synchronized State exportState() { return new State(List.copyOf(trials.values())); }

    /** Restore evidence only. Pending actions, body ownership and speculative predictions are absent. */
    public synchronized void importState(State state) {
        trials.clear();
        if (state == null || state.trials() == null) return;
        List<Trial> bounded = state.trials().subList(Math.max(0, state.trials().size() - MAX_TRIALS), state.trials().size());
        for (Trial trial : bounded) {
            if (trial == null || !Double.isFinite(trial.loss()) || trial.loss() < 0
                    || !valid(trial.evidenceId(), 160) || !valid(trial.mechanism(), 256)
                    || !valid(trial.tool(), 80) || !valid(trial.predicate(), 128)
                    || !valid(trial.outcome(), 256)
                    || !valid(trial.context(), 64)
                    || !trial.context().matches("[a-f0-9]{64}")
                    || !valid(trial.environment(), 512) || trial.before() == null
                    || trial.before().length() > 256 || trial.tick() < 0) continue;
            try {
                if (trials.values().stream().anyMatch(saved -> saved.evidenceId().equals(trial.evidenceId()))) continue;
                String normalized = abstractArguments(trial.arguments());
                String key = condition(trial.mechanism(), trial.tool(), normalized,
                        trial.predicate(), trial.before(), trial.environment()) + "|" + trial.context();
                Trial previous = trials.get(key);
                if (previous == null || previous.tick() < trial.tick()) {
                    trials.put(key, new Trial(trial.evidenceId(), trial.mechanism(), trial.tool(),
                            normalized, trial.predicate(), trial.before(), trial.outcome(), trial.context(),
                            trial.environment(), trial.tick(), Math.min(14.0, trial.loss())));
                }
            } catch (IllegalArgumentException ignored) { /* quarantine only the invalid entry */ }
        }
        while (trials.size() > MAX_TRIALS) trials.remove(trials.keySet().iterator().next());
    }

    private static String condition(String mechanism, String tool, String arguments,
                                    String predicate, String before, String environment) {
        return digestParts(mechanism, tool, arguments, predicate, before, environment);
    }

    static String abstractArguments(String arguments) {
        if (arguments == null || arguments.length() > MAX_ARGUMENT_CHARS) {
            throw new IllegalArgumentException("Probe arguments are too large");
        }
        try {
            JsonElement parsed = JsonParser.parseString(arguments);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Probe arguments must be an object");
            JsonObject result = new JsonObject();
            // Only top-level spatial coordinates are interchangeable. IDs, faces, slots,
            // amounts, dimension names and nested machine settings must match exactly.
            new TreeMap<>(parsed.getAsJsonObject().asMap()).forEach((key, value) -> {
                if (!key.equals("x") && !key.equals("y") && !key.equals("z")) {
                    result.add(key, canonical(value, 0));
                }
            });
            return result.toString();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid probe arguments", invalid);
        }
    }

    private static JsonElement canonical(JsonElement value, int depth) {
        if (depth > 12) throw new IllegalArgumentException("Probe arguments are too deeply nested");
        if (value.isJsonObject()) {
            JsonObject object = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach(
                    (key, nested) -> object.add(key, canonical(nested, depth + 1)));
            return object;
        }
        if (value.isJsonArray()) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            value.getAsJsonArray().forEach(nested -> array.add(canonical(nested, depth + 1)));
            return array;
        }
        return value.deepCopy();
    }

    private static double entropy(double[] alpha, double total) {
        double entropy = 0;
        for (double value : alpha) {
            double probability = value / total;
            entropy -= probability * Math.log(probability) / Math.log(2);
        }
        return entropy;
    }

    private static double progress(List<Trial> matching) {
        if (matching.size() < 4) return 0;
        List<Trial> recent = matching.stream().sorted(Comparator.comparingLong(Trial::tick))
                .skip(Math.max(0, matching.size() - 16)).toList();
        int half = recent.size() / 2;
        double early = recent.subList(0, half).stream().mapToDouble(Trial::loss).average().orElse(0);
        double late = recent.subList(half, recent.size()).stream().mapToDouble(Trial::loss).average().orElse(0);
        return Math.max(0, early - late);
    }

    private static boolean valid(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    private static double finiteUnit(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String digestParts(String... parts) {
        StringBuilder encoded = new StringBuilder();
        for (String part : parts) encoded.append(part.length()).append(':').append(part);
        return digest(encoded.toString());
    }
}
