package ninja.trek.mc.goldgolem.api.structure;

/** Raised when a public procedural template cannot be loaded or validated. */
public final class TemplateLoadException extends Exception {
    public TemplateLoadException(String message) {
        super(message);
    }

    public TemplateLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
