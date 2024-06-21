package ch.rupfizupfi.deck.testrunner;

interface SignalListener {
    void handleSignal(int signal) throws FinishTestException;
}
