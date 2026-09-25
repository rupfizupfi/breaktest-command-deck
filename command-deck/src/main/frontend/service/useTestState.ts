import {useEffect, useRef, useState} from "react";
import {TestRunnerService} from "Frontend/generated/endpoints";
import {ConnectionState, getService, TestStateFrame} from "Frontend/service/StatusService";
import {useLiveStatus} from "Frontend/service/useLiveStatus";

/**
 * Subscribes a component to the run's state, and keeps StatusService seeded from the server.
 *
 * Kept out of StatusService for the same two reasons useLiveStatus is: the service stays free of
 * React, and it stays free of endpoint calls. The seed lives here.
 *
 * The seed is what lets a browser that joins mid-incident rebuild its banner immediately instead of
 * waiting up to 2 s for the next broadcast — and, on a reconnect, what lets a browser that missed the
 * transition entirely find out it happened.
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
        // Mount, and every socket re-open. Deliberately not on the way down: that call would go to
        // the same server the socket just lost, and rpcErrorPolicy would toast the failure on every
        // reconnect cycle — noise stacked on a connection loss the banner already annunciates.
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

                // No run means no incident, and this read is authoritative — it is the one thing
                // allowed to clear a banner.
                if (!status || !status.isRunning) {
                    service.applyTestState(null);
                    return;
                }

                // Hilla types every endpoint result and every nested field as possibly absent. A
                // frame without an id or a state cannot be ordered against later frames, so it is
                // treated as no run at all rather than as an incident nothing can supersede.
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
                // An unreachable endpoint is not evidence that the run ended, so the last known
                // state stands. Silent on purpose: the connection itself is already annunciated.
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
