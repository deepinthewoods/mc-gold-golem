package ninja.trek.mc.goldgolem.api.structure;

import java.util.List;

/** Structured outcome of a synchronous build request. */
public record BuildResult(
        Status status,
        int generatedBlocks,
        int placedBlocks,
        int skippedOccupied,
        int loadedChunks,
        List<String> diagnostics
) {
    public BuildResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean succeeded() {
        return status == Status.SUCCESS || status == Status.CAPPED;
    }

    public enum Status {
        SUCCESS,
        CAPPED,
        INVALID_REQUEST,
        WRONG_THREAD,
        GENERATION_FAILED,
        PLACEMENT_FAILED
    }
}
