package ninja.trek.mc.goldgolem.api.structure;

import java.util.List;
import java.util.Optional;

/** Structured result for expected planning success or failure. */
public record DungeonPlanResult(Status status, DungeonPlan plan, List<String> diagnostics) {
    public DungeonPlanResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if ((status == Status.SUCCESS) != (plan != null)) {
            throw new IllegalArgumentException("Only successful planning results may contain a plan");
        }
    }

    public boolean succeeded() { return status == Status.SUCCESS; }
    public Optional<DungeonPlan> optionalPlan() { return Optional.ofNullable(plan); }

    public enum Status {
        SUCCESS,
        INVALID_TEMPLATE,
        UNSATISFIABLE,
        SEARCH_LIMIT_REACHED
    }
}
