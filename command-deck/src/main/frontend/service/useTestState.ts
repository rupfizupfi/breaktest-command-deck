import {useEffect, useRef, useState} from "react";
import {TestRunnerService} from "Frontend/generated/endpoints";
import {ConnectionState, getService, TestStateFrame} from "Frontend/service/StatusService";
import {useLiveStatus} from "Frontend/service/useLiveStatus";

/**
 * Subscribes a component to the run's state, and seeds StatusService from the server (kept here so
 * the service stays free of React and endpoint calls).
 *
 * The seed lets a browser that joins mid-incident, or reconnects after missing a transition, show
 * the right banner without waiting up to 2 s for the next broadcast.
 */
export function useTestState(): TestStateFrame | null {
    const service = getService();
    const {connected} = useLiveStatus();
    const [frame, setFrame] = useState<TestStateFrame | null>(() => service.currentTestState);
    const seeded = useRef(false);

    useEffect(() => {
        const subscription = service.testState.subscribe(setFrame);
        return () => subscription.unsubscribe();
    }, [service]);

    useEffect(() => {
        // On mount and every socket re-open, not on the way down: the call would fail against the
        // lost server and rpcErrorPolicy would toast it on every reconnect cycle.
        if (!connected && seeded.current) {
            return;
        }
        seeded.current = true;

        let cancelled = false;

        TestRunnerService.status()
            .then((status) => {
                if (cancelled) {
                    return;
                }

                // Authoritative: no run means no incident.
                if (!status || !status.isRunning) {
                    service.applyTestState(null);
                    return;
                }

                // A frame without id or state cannot be ordered against later frames, so it counts
                // as no run rather than an incident nothing can supersede.
                const id = status.testResult?.id;
                const state = status.state;
                if (id === undefined || state === undefined) {
                    service.applyTestState(null);
                    return;
                }

                service.applyTestState({
                    state,
                    reason: status.reason ?? null,
                    canResume: status.canResume ?? false,
                    lossCount: status.lossCount ?? 0,
                    reconnectAttempt: status.reconnectAttempt ?? 0,
                    lastKnownForce: status.lastKnownForce ?? null,
                    safeHoldDeadlineMillis: status.safeHoldDeadlineMillis ?? null,
                    testResultId: id,
                    sequence: status.sequence ?? 0,
                });
            })
            .catch(() => {
                // Unreachable is not evidence the run ended; the last known state stands. The
                // connection loss is already shown.
            });

        return () => {
            cancelled = true;
        };
    }, [service, connected]);

    return frame;
}

/** The one warning a view should render about how much of what it shows is live. */
export function useConnectionState(): ConnectionState {
    const service = getService();
    const [state, setState] = useState<ConnectionState>(() => service.currentConnectionState);

    useEffect(() => {
        const subscription = service.connectionState.subscribe(setState);
        return () => subscription.unsubscribe();
    }, [service]);

    return state;
}
