package ch.rupfizupfi.deck.testrunner;

public interface SignalListener {
    void handleSignal(int signal) throws FinishTestException;
}
