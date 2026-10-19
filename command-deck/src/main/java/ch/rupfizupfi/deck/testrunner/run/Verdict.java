package ch.rupfizupfi.deck.testrunner.run;

/**
 * What a program answers to a crossing or a timed turn. Replaces the finish exception as the
 * way a program ends its run: a value the loop acts on, not an exception unwinding the runner.
 */
public sealed interface Verdict {
    Verdict CONTINUE = new Continue();

    static Verdict finish(FinishCause cause) {
        return new Finish(cause);
    }

    record Continue() implements Verdict {
    }

    record Finish(FinishCause cause) implements Verdict {
    }
}
