package ch.rupfizupfi.deck.testrunner;

/**
 * Notified when the load-cell watchdog decides the force signal is gone.
 * <p>
 * Implementations run SYNCHRONOUSLY on the load-cell measurement thread, on purpose: the runner
 * thread is normally parked in {@code TestContext.processSignals()}, which is precisely the
 * situation this watchdog exists for, so handing the loss to the signal queue would leave the motor
 * running until something else happens to wake that thread. This callback is the shortest path from
 * "no more force samples" to a drive de-energize.
 * <p>
 * The cost of that: an implementation must not block on I/O and must not wait for a reconnect. Hand
 * anything slow to an executor - while this call is running, no measurement is being read.
 *
 * @see TestStateMachine for the transition the handler is expected to make
 */
public interface SensorLossListener {

    /**
     * @param reason         which detector tripped and what it saw, for the log and the operator banner
     * @param lastKnownForce the last force considered valid, in newton; the drift gate compares a
     *                       reconnected sensor against this value
     */
    void onSensorLoss(String reason, float lastKnownForce);

    /**
     * The measurement loop itself died - a full disk, a writer failure, anything that is not the
     * sensor going away. Distinct from {@link #onSensorLoss} because there is nothing to reconnect
     * to and no hold worth entering: the run is over. It exists so that ending is recorded as a
     * FAULT rather than as a finish, which is what a bare stop signal would look like from the
     * runner thread - and a run that died on a full disk must not sit in the results table
     * indistinguishable from one that reached its end condition.
     * <p>
     * The motor has already been stopped by the caller before this is invoked.
     *
     * @param detail what failed, for the log and the incident record
     */
    default void onWatchdogFailure(String detail) {
    }
}
