package ninja.trek.mc.goldgolem.api.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable copy of one build mode's material-gradient configuration. */
final class GradientConfig {
    static final GradientConfig EMPTY = new GradientConfig(Map.of(), List.of(), List.of(), List.of());

    private final Map<String, Integer> blockGroups;
    private final List<List<String>> groupSlots;
    private final List<Float> groupWindows;
    private final List<Integer> groupNoiseScales;

    GradientConfig(
            Map<String, Integer> blockGroups,
            List<List<String>> groupSlots,
            List<Float> groupWindows,
            List<Integer> groupNoiseScales
    ) {
        this.blockGroups = Collections.unmodifiableMap(new LinkedHashMap<>(blockGroups));
        List<List<String>> slotsCopy = new ArrayList<>(groupSlots.size());
        for (List<String> slots : groupSlots) {
            slotsCopy.add(List.copyOf(slots));
        }
        this.groupSlots = List.copyOf(slotsCopy);
        this.groupWindows = List.copyOf(groupWindows);
        this.groupNoiseScales = List.copyOf(groupNoiseScales);
    }

    static GradientConfig capture(
            Map<String, Integer> blockGroups,
            List<String[]> groupSlots,
            List<Float> groupWindows,
            List<Integer> groupNoiseScales
    ) {
        List<List<String>> slots = new ArrayList<>(groupSlots.size());
        for (String[] group : groupSlots) {
            List<String> normalized = new ArrayList<>(group.length);
            for (String value : group) normalized.add(value == null ? "" : value);
            slots.add(normalized);
        }
        return new GradientConfig(blockGroups, slots, groupWindows, groupNoiseScales);
    }

    boolean isEmpty() {
        return blockGroups.isEmpty() && groupSlots.isEmpty();
    }

    Map<String, Integer> blockGroups() {
        return blockGroups;
    }

    List<List<String>> groupSlots() {
        return groupSlots;
    }

    List<Float> groupWindows() {
        return groupWindows;
    }

    List<Integer> groupNoiseScales() {
        return groupNoiseScales;
    }
}
