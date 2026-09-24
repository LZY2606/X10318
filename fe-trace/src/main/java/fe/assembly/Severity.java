package fe.assembly;

public enum Severity {
    /** Informational; assembly still has a unique well-defined answer. */
    INFO,
    /** A value could not be converted or used; it stays in place. */
    WARNING,
    /** The situation is ambiguous / invalid; multiple or no candidates exist. */
    ERROR
}
