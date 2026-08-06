package com.mineagent.engine.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Evidence-backed index of assets the companion can currently use or has
 * actually observed in the world.
 *
 * <p>The index deliberately stores registered identifiers, positions and
 * affordances instead of special-casing individual vanilla objects. A bed, a
 * modded machine and a tool in a chest therefore participate in the same
 * lookup: where is it, what can it do, how fresh is that evidence, and what
 * action is needed to use it. Container contents are recorded only after a
 * real inspection or a transfer performed by this mod; this avoids remote
 * chest x-ray while still giving the planner durable object permanence.
 */
public final class WorldAssetIndex {

    public enum Scope {
        INVENTORY,
        EQUIPPED,
        KNOWN_CONTAINER,
        OPEN_MENU,
        WORLD_OBJECT,
        DROPPED_ITEM
    }

    public record Position(String dimension, int x, int y, int z) {
        public Position {
            dimension = normalize(dimension, "minecraft:overworld");
        }

        public double distanceTo(Position other) {
            if (other == null || !dimension.equals(other.dimension)) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public String compact() {
            return dimension + ":" + x + "," + y + "," + z;
        }
    }

    /** A Minecraft-free description produced by the server-side observer. */
    public record ItemObservation(int slot, String resourceId, int count,
                                  int durability, int maxDurability,
                                  Set<String> capabilities, double quality) {
        public ItemObservation {
            resourceId = normalizeId(resourceId);
            count = Math.max(0, count);
            durability = Math.max(0, durability);
            maxDurability = Math.max(0, maxDurability);
            capabilities = normalizeCapabilities(capabilities);
            quality = finiteNonNegative(quality);
        }
    }

    /** A block, dropped stack or other positioned object observed in-world. */
    public record WorldObservation(String identity, String resourceId,
                                   String kind, Position position, int count,
                                   int durability, int maxDurability,
                                   Set<String> capabilities, double quality,
                                   double confidence) {
        public WorldObservation {
            identity = normalize(identity, "object");
            resourceId = normalizeId(resourceId);
            kind = normalize(kind, "world_object");
            Objects.requireNonNull(position, "position");
            count = Math.max(1, count);
            durability = Math.max(0, durability);
            maxDurability = Math.max(0, maxDurability);
            capabilities = normalizeCapabilities(capabilities);
            quality = finiteNonNegative(quality);
            confidence = unit(confidence);
        }
    }

    public record Asset(String key, Scope scope, String resourceId, String kind,
                        int count, int slot, int durability, int maxDurability,
                        Position position, String containerId,
                        Set<String> capabilities, double quality,
                        long observedTick, double confidence) {
        public Asset {
            key = normalize(key, "asset");
            scope = Objects.requireNonNullElse(scope, Scope.WORLD_OBJECT);
            resourceId = normalizeId(resourceId);
            kind = normalize(kind, "item");
            count = Math.max(0, count);
            durability = Math.max(0, durability);
            maxDurability = Math.max(0, maxDurability);
            containerId = blankToNull(containerId);
            capabilities = normalizeCapabilities(capabilities);
            quality = finiteNonNegative(quality);
            observedTick = Math.max(0L, observedTick);
            confidence = unit(confidence);
        }

        public boolean carried() {
            return scope == Scope.INVENTORY || scope == Scope.EQUIPPED;
        }

        public boolean stored() {
            return scope == Scope.KNOWN_CONTAINER || scope == Scope.OPEN_MENU;
        }
    }

    public record Candidate(String action, Asset asset, double distance,
                            long ageTicks, String reason) {
        public Candidate {
            action = normalize(action, "inspect");
            distance = Double.isFinite(distance) ? Math.max(0.0, distance)
                    : Double.POSITIVE_INFINITY;
            ageTicks = Math.max(0L, ageTicks);
            reason = normalize(reason, "Observed candidate");
        }
    }

    public record NeedResolution(String kind, String target, int desiredCount,
                                 int carriedExactCount, boolean satisfiedNow,
                                 List<Candidate> candidates,
                                 String recommendedAction, long revision) {
        public NeedResolution {
            kind = normalize(kind, "item");
            target = normalize(target, "unknown");
            desiredCount = Math.max(1, desiredCount);
            carriedExactCount = Math.max(0, carriedExactCount);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            recommendedAction = normalize(recommendedAction, "observe_or_acquire");
            revision = Math.max(0L, revision);
        }
    }

    public record ReuseAssessment(int carriedExactCount,
                                  List<Candidate> storedExact,
                                  List<Candidate> storedSubstitutes,
                                  List<Candidate> worldExact,
                                  long revision) {
        public ReuseAssessment {
            carriedExactCount = Math.max(0, carriedExactCount);
            storedExact = storedExact == null ? List.of() : List.copyOf(storedExact);
            storedSubstitutes = storedSubstitutes == null
                    ? List.of() : List.copyOf(storedSubstitutes);
            worldExact = worldExact == null ? List.of() : List.copyOf(worldExact);
            revision = Math.max(0L, revision);
        }

        public int knownStoredExactCount() {
            return storedExact.stream().mapToInt(value -> value.asset().count()).sum();
        }
    }

    public record State(List<Asset> assets, long revision) {}

    private static final int MAX_ASSETS = 768;
    private static final int MAX_WORLD_OBJECTS_PER_RESOURCE = 48;
    private static final int MAX_PERSISTED_ASSETS = 256;
    private static final int MAX_RESOLUTION_CANDIDATES = 16;
    private static final EnumSet<Scope> CONTAINER_SCOPES =
            EnumSet.of(Scope.KNOWN_CONTAINER, Scope.OPEN_MENU);

    private final LinkedHashMap<String, Asset> assets = new LinkedHashMap<>();
    private long revision;

    /** Replace the full player inventory snapshot with authoritative contents. */
    public synchronized void observeInventory(Position playerPosition,
                                              Collection<ItemObservation> observed,
                                              long gameTick) {
        assets.entrySet().removeIf(entry -> entry.getValue().carried());
        if (observed != null) {
            for (ItemObservation item : observed) {
                if (!validItem(item)) continue;
                Scope scope = item.slot() >= 36 ? Scope.EQUIPPED : Scope.INVENTORY;
                String key = "inventory:" + item.slot();
                assets.put(key, new Asset(key, scope, item.resourceId(), "item",
                        item.count(), item.slot(), item.durability(), item.maxDurability(),
                        playerPosition, null, item.capabilities(), item.quality(),
                        gameTick, 1.0));
            }
        }
        changed();
    }

    /**
     * Replace one container's observed contents. An empty observation is
     * meaningful: it invalidates previously remembered contents at that exact
     * container rather than leaving phantom items in memory.
     */
    public synchronized void observeContainer(String containerId, Position position,
                                              Collection<ItemObservation> observed,
                                              boolean durableLocation,
                                              long gameTick) {
        String normalizedContainer = normalize(containerId, "container");
        assets.entrySet().removeIf(entry -> {
            Asset asset = entry.getValue();
            return asset.stored() && normalizedContainer.equals(asset.containerId());
        });
        Scope scope = durableLocation ? Scope.KNOWN_CONTAINER : Scope.OPEN_MENU;
        if (observed != null) {
            for (ItemObservation item : observed) {
                if (!validItem(item)) continue;
                String key = "container:" + normalizedContainer + ":" + item.slot();
                assets.put(key, new Asset(key, scope, item.resourceId(), "item",
                        item.count(), item.slot(), item.durability(), item.maxDurability(),
                        position, normalizedContainer, item.capabilities(), item.quality(),
                        gameTick, durableLocation ? 0.98 : 0.75));
            }
        }
        changed();
    }

    /** Reconcile positioned blocks for one fully scanned observation volume. */
    public synchronized void observeWorldObject(WorldObservation object,
                                                long gameTick) {
        if (object == null || object.position() == null) return;
        String key = "world:" + object.position().compact() + ":" + object.identity();
        // A block position can hold only one block. Executor-verified placement
        // therefore invalidates any older world-object identity at that cell.
        assets.entrySet().removeIf(entry -> entry.getValue().scope() == Scope.WORLD_OBJECT
                && object.position().equals(entry.getValue().position()));
        assets.put(key, new Asset(key, Scope.WORLD_OBJECT,
                object.resourceId(), object.kind(), object.count(), -1,
                object.durability(), object.maxDurability(), object.position(),
                null, object.capabilities(), object.quality(), gameTick,
                object.confidence()));
        changed();
    }

    /** Reconcile positioned blocks for one fully scanned observation volume. */
    public synchronized void reconcileWorldObjects(Position center, int radius,
                                                   int minYOffset, int maxYOffset,
                                                   Collection<WorldObservation> observed,
                                                   long gameTick) {
        Set<String> seen = new LinkedHashSet<>();
        if (observed != null) {
            for (WorldObservation object : observed) {
                if (object == null || object.position() == null) continue;
                String key = "world:" + object.position().compact() + ":" + object.identity();
                seen.add(key);
                assets.put(key, new Asset(key, Scope.WORLD_OBJECT,
                        object.resourceId(), object.kind(), object.count(), -1,
                        object.durability(), object.maxDurability(), object.position(),
                        null, object.capabilities(), object.quality(), gameTick,
                        object.confidence()));
            }
        }
        if (center != null) {
            assets.entrySet().removeIf(entry -> {
                Asset asset = entry.getValue();
                Position pos = asset.position();
                if (asset.scope() != Scope.WORLD_OBJECT || pos == null
                        || !center.dimension().equals(pos.dimension())) return false;
                int dx = Math.abs(pos.x() - center.x());
                int dz = Math.abs(pos.z() - center.z());
                int dy = pos.y() - center.y();
                return dx <= radius && dz <= radius
                        && dy >= minYOffset && dy <= maxYOffset
                        && !seen.contains(entry.getKey());
            });
        }
        changed();
    }

    /** Reconcile visible dropped stacks without affecting remembered blocks. */
    public synchronized void reconcileDroppedItems(Position center, int radius,
                                                   Collection<WorldObservation> observed,
                                                   long gameTick) {
        Set<String> seen = new LinkedHashSet<>();
        if (observed != null) {
            for (WorldObservation object : observed) {
                if (object == null || object.position() == null) continue;
                String key = "drop:" + object.identity();
                seen.add(key);
                assets.put(key, new Asset(key, Scope.DROPPED_ITEM,
                        object.resourceId(), "dropped_item", object.count(), -1,
                        object.durability(), object.maxDurability(), object.position(),
                        null, object.capabilities(), object.quality(), gameTick,
                        object.confidence()));
            }
        }
        if (center != null) {
            assets.entrySet().removeIf(entry -> {
                Asset asset = entry.getValue();
                Position pos = asset.position();
                return asset.scope() == Scope.DROPPED_ITEM && pos != null
                        && center.dimension().equals(pos.dimension())
                        && center.distanceTo(pos) <= radius + 2.0
                        && !seen.contains(entry.getKey());
            });
        }
        changed();
    }

    /** Forget a world position after executor evidence proves it changed. */
    public synchronized void invalidatePosition(Position position) {
        if (position == null) return;
        boolean removed = assets.entrySet().removeIf(entry -> {
            Asset asset = entry.getValue();
            return asset.position() != null && asset.position().equals(position)
                    && (asset.scope() == Scope.WORLD_OBJECT
                    || asset.scope() == Scope.KNOWN_CONTAINER);
        });
        if (removed) changed();
    }

    public synchronized NeedResolution resolve(String kind, String target,
                                               int desiredCount,
                                               Position currentPosition,
                                               long gameTick) {
        String normalizedKind = "capability".equalsIgnoreCase(kind)
                ? "capability" : "item";
        String normalizedTarget = normalizedKind.equals("item")
                ? normalizeId(target) : normalizeCapability(target);
        int desired = Math.max(1, desiredCount);
        int carriedExact = normalizedKind.equals("item")
                ? countCarriedExact(normalizedTarget) : 0;
        List<Candidate> candidates = new ArrayList<>();
        for (Asset asset : assets.values()) {
            boolean matches = normalizedKind.equals("item")
                    ? asset.resourceId().equals(normalizedTarget)
                    : asset.capabilities().contains(normalizedTarget);
            if (!matches || asset.count() <= 0) continue;
            candidates.add(candidateFor(asset, currentPosition, gameTick));
        }
        candidates.sort(candidateComparator());
        if (candidates.size() > MAX_RESOLUTION_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_RESOLUTION_CANDIDATES));
        }

        boolean capabilityCarried = normalizedKind.equals("capability")
                && candidates.stream().anyMatch(value -> value.asset().carried());
        boolean satisfied = normalizedKind.equals("item")
                ? carriedExact >= desired : capabilityCarried;
        String recommendation;
        if (satisfied) recommendation = "reuse_carried";
        else if (candidates.stream().anyMatch(value -> value.asset().stored())) {
            recommendation = "verify_and_retrieve_known_storage";
        } else if (candidates.stream().anyMatch(value ->
                value.asset().scope() == Scope.DROPPED_ITEM)) {
            recommendation = "collect_observed_drop";
        } else if (candidates.stream().anyMatch(value ->
                value.asset().scope() == Scope.WORLD_OBJECT)) {
            recommendation = "reuse_or_interact_with_world_asset";
        } else recommendation = "inspect_recipes_or_acquire_inputs";
        return new NeedResolution(normalizedKind, normalizedTarget, desired,
                carriedExact, satisfied, candidates, recommendation, revision);
    }

    /** Acquisition preflight used by craft and future production tools. */
    public synchronized ReuseAssessment assessAcquisition(
            String resourceId, int desiredTotal, Set<String> substituteCapabilities,
            double minimumQuality, Position currentPosition, long gameTick) {
        String id = normalizeId(resourceId);
        Set<String> substitutes = normalizeCapabilities(substituteCapabilities);
        int carried = countCarriedExact(id);
        List<Candidate> exact = new ArrayList<>();
        List<Candidate> equivalent = new ArrayList<>();
        List<Candidate> world = new ArrayList<>();
        for (Asset asset : assets.values()) {
            if (asset.count() <= 0) continue;
            if (asset.scope() == Scope.WORLD_OBJECT && asset.resourceId().equals(id)) {
                world.add(candidateFor(asset, currentPosition, gameTick));
                continue;
            }
            if (!asset.stored()) continue;
            if (asset.resourceId().equals(id)) {
                exact.add(candidateFor(asset, currentPosition, gameTick));
            } else if (desiredTotal <= 1 && !substitutes.isEmpty()
                    && asset.quality() >= minimumQuality
                    && asset.capabilities().stream().anyMatch(substitutes::contains)) {
                equivalent.add(candidateFor(asset, currentPosition, gameTick));
            }
        }
        exact.sort(candidateComparator());
        equivalent.sort(candidateComparator());
        world.sort(candidateComparator());
        return new ReuseAssessment(carried, limited(exact), limited(equivalent),
                limited(world), revision);
    }

    public synchronized List<Asset> snapshot() {
        return List.copyOf(assets.values());
    }

    public synchronized State exportState() {
        // Open menus and carried slots are live session facts. Persisting them
        // would resurrect stale inventory after a restart; the next server
        // snapshot repopulates carried state authoritatively.
        List<Asset> durable = assets.values().stream()
                .filter(WorldAssetIndex::persistentAsset)
                .sorted(Comparator.comparingInt(WorldAssetIndex::persistenceRank)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(Asset::observedTick)
                                .reversed()))
                .limit(MAX_PERSISTED_ASSETS)
                .toList();
        return new State(durable, revision);
    }

    public synchronized void importState(State state) {
        assets.clear();
        if (state != null && state.assets() != null) {
            for (Asset asset : state.assets()) {
                if (!validAsset(asset) || !persistentAsset(asset)) continue;
                assets.put(asset.key(), asset);
            }
            revision = Math.max(0L, state.revision());
        }
        trim();
        revision++;
    }

    public synchronized String summarizeForPrompt(Position currentPosition,
                                                  long gameTick) {
        Map<String, Integer> carried = new LinkedHashMap<>();
        for (Asset asset : assets.values()) {
            if (asset.carried()) carried.merge(asset.resourceId(), asset.count(), Integer::sum);
        }
        StringBuilder out = new StringBuilder("World assets (observed evidence):\n");
        if (carried.isEmpty()) {
            out.append("- carried: empty or not yet observed\n");
        } else {
            out.append("- carried: ");
            carried.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .limit(14)
                    .forEach(entry -> out.append(entry.getKey()).append('x')
                            .append(entry.getValue()).append(' '));
            out.append('\n');
        }

        List<Asset> useful = assets.values().stream()
                .filter(asset -> asset.stored() || asset.scope() == Scope.WORLD_OBJECT)
                // Facilities and verified storage must not be hidden by a
                // nearby ore vein or hundreds of water observations.
                .sorted(Comparator.comparingInt(WorldAssetIndex::promptRank)
                        .thenComparingDouble(
                                 (Asset asset) -> distance(asset, currentPosition))
                        .thenComparing(Asset::key))
                .limit(10).toList();
        for (Asset asset : useful) {
            out.append("- ").append(asset.stored() ? "stored " : "world ")
                    .append(asset.resourceId());
            if (asset.count() > 1) out.append('x').append(asset.count());
            if (asset.position() != null) out.append(" @ ").append(asset.position().compact());
            if (!asset.capabilities().isEmpty()) {
                out.append(" can=").append(String.join(",", asset.capabilities()));
            }
            out.append(" age_ticks=").append(age(asset, gameTick)).append('\n');
        }
        out.append("Use resolve_need before producing a replacement when ownership or location is uncertain.\n");
        return out.toString();
    }

    private int countCarriedExact(String resourceId) {
        return assets.values().stream().filter(Asset::carried)
                .filter(asset -> asset.resourceId().equals(resourceId))
                .mapToInt(Asset::count).sum();
    }

    private static Candidate candidateFor(Asset asset, Position current, long tick) {
        String action = switch (asset.scope()) {
            case INVENTORY, EQUIPPED -> "reuse_carried";
            case KNOWN_CONTAINER -> "verify_and_retrieve";
            case OPEN_MENU -> "retrieve_from_open_menu";
            case WORLD_OBJECT -> "reuse_world_object";
            case DROPPED_ITEM -> "collect_drop";
        };
        String reason = asset.scope() == Scope.KNOWN_CONTAINER
                ? "Container content is remembered from a real observation; verify before transfer"
                : "Grounded by a live or executor observation";
        return new Candidate(action, asset, distance(asset, current), age(asset, tick), reason);
    }

    private static Comparator<Candidate> candidateComparator() {
        return Comparator.comparingInt((Candidate candidate) -> scopeRank(candidate.asset().scope()))
                .thenComparingDouble(Candidate::distance)
                .thenComparingLong(Candidate::ageTicks)
                .thenComparing(candidate -> candidate.asset().key());
    }

    private static int scopeRank(Scope scope) {
        return switch (scope) {
            case INVENTORY, EQUIPPED -> 0;
            case OPEN_MENU -> 1;
            case KNOWN_CONTAINER -> 2;
            case DROPPED_ITEM -> 3;
            case WORLD_OBJECT -> 4;
        };
    }

    private static List<Candidate> limited(List<Candidate> values) {
        return values.size() <= MAX_RESOLUTION_CANDIDATES ? List.copyOf(values)
                : List.copyOf(values.subList(0, MAX_RESOLUTION_CANDIDATES));
    }

    private void changed() {
        revision++;
        trim();
    }

    private void trim() {
        Map<String, List<Asset>> resourceGroups = new LinkedHashMap<>();
        for (Asset asset : assets.values()) {
            if (asset.scope() != Scope.WORLD_OBJECT || importantWorldAsset(asset)) continue;
            resourceGroups.computeIfAbsent(asset.position() == null
                            ? asset.resourceId()
                            : asset.position().dimension() + '|' + asset.resourceId(),
                    ignored -> new ArrayList<>()).add(asset);
        }
        for (List<Asset> group : resourceGroups.values()) {
            int limit = group.isEmpty() || !"water".equals(group.getFirst().kind())
                    ? MAX_WORLD_OBJECTS_PER_RESOURCE : 16;
            if (group.size() <= limit) continue;
            group.sort(Comparator.comparingLong(Asset::observedTick).reversed()
                    .thenComparing(Asset::key));
            for (int index = limit; index < group.size(); index++) {
                assets.remove(group.get(index).key());
            }
        }
        while (assets.size() > MAX_ASSETS) {
            String removable = assets.values().stream()
                    .filter(asset -> !asset.carried())
                    .min(Comparator.comparingInt(WorldAssetIndex::persistenceRank)
                            .thenComparingLong(Asset::observedTick))
                    .map(Asset::key).orElse(assets.keySet().iterator().next());
            assets.remove(removable);
        }
    }

    private static boolean persistentAsset(Asset asset) {
        return asset != null && (asset.scope() == Scope.KNOWN_CONTAINER
                || (asset.scope() == Scope.WORLD_OBJECT && importantWorldAsset(asset)));
    }

    private static boolean importantWorldAsset(Asset asset) {
        if (asset == null) return false;
        String kind = asset.kind();
        return kind.startsWith("station_") || kind.equals("storage")
                || kind.equals("bed") || kind.equals("portal")
                || kind.equals("block_entity")
                || asset.capabilities().contains("world:placed");
    }

    private static int persistenceRank(Asset asset) {
        if (asset == null) return 0;
        if (asset.scope() == Scope.KNOWN_CONTAINER || asset.stored()) return 3;
        if (asset.scope() == Scope.WORLD_OBJECT && importantWorldAsset(asset)) return 2;
        return 0;
    }

    private static int promptRank(Asset asset) {
        return 3 - persistenceRank(asset);
    }

    private static boolean validItem(ItemObservation item) {
        return item != null && item.count() > 0 && !"minecraft:air".equals(item.resourceId());
    }

    private static boolean validAsset(Asset asset) {
        return asset != null && asset.key() != null && !asset.key().isBlank()
                && asset.scope() != null && asset.resourceId() != null
                && !asset.resourceId().isBlank() && asset.count() > 0
                && Double.isFinite(asset.quality()) && Double.isFinite(asset.confidence());
    }

    private static double distance(Asset asset, Position current) {
        return asset.position() == null ? Double.POSITIVE_INFINITY
                : asset.position().distanceTo(current);
    }

    private static long age(Asset asset, long tick) {
        return tick >= asset.observedTick() ? tick - asset.observedTick() : 0L;
    }

    private static Set<String> normalizeCapabilities(Collection<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String capability = normalizeCapability(value);
            if (!capability.equals("unknown")) normalized.add(capability);
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeCapability(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_:./-]+", "_");
    }

    private static String normalizeId(String value) {
        String id = normalize(value, "minecraft:air").toLowerCase(Locale.ROOT);
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double unit(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }
}
