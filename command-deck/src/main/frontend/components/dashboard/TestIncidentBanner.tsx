import React, {useEffect, useRef, useState} from 'react';
import {Button} from '@vaadin/react-components/Button.js';
import {ConfirmDialog} from '@vaadin/react-components/ConfirmDialog.js';
import {TestRunnerService} from 'Frontend/generated/endpoints';
import TestState from 'Frontend/generated/ch/rupfizupfi/deck/testrunner/TestState';
import {isIncident} from 'Frontend/service/StatusService';
import {useTestState} from 'Frontend/service/useTestState';

/** The exact type the endpoint returns, without betting on the generated file's path. */
type CommandResponse = Awaited<ReturnType<typeof TestRunnerService.resume>>;

const COUNTDOWN_TICK_MS = 1000;

function formatDuration(millis: number): string {
    const seconds = Math.max(0, Math.round(millis / 1000));
    if (seconds < 60) {
        return `${seconds} s`;
    }
    const rest = seconds % 60;
    return rest === 0 ? `${Math.floor(seconds / 60)} min` : `${Math.floor(seconds / 60)} min ${rest} s`;
}

function headline(state: TestState): string {
    switch (state) {
        case TestState.SENSOR_LOST:
            return 'SENSOR LOST — the motor has been stopped';
        case TestState.SAFE_HOLD:
            return 'SAFE HOLD — the motor is stopped, the run is waiting for you';
        case TestState.RESUMING:
            return 'RESUMING — re-arming the watchdog before the drive';
        default:
            return 'SENSOR LOST';
    }
}

/**
 * Annunciates a sensor-loss incident and carries the two commands that end it.
 *
 * Self-contained in the same sense as SimulatedModeBanner, and for the same reason: a safety
 * annunciator must not be able to take the app shell down with it. Every promise is caught, and the
 * render falls back to a plain sentence rather than throwing into the tree — a banner that renders
 * badly is recoverable, one that unmounts the view around it is not.
 *
 * Nothing here is optimistic. The buttons show what the server answered, including a refusal and its
 * reason; the state they read back is the server's, never a locally assumed one.
 */
export default function TestIncidentBanner(): React.JSX.Element | null {
    const frame = useTestState();
    const incident = frame !== null && isIncident(frame.state);

    const [now, setNow] = useState<number>(() => Date.now());
    const [confirming, setConfirming] = useState(false);
    const [refusal, setRefusal] = useState<string | null>(null);
    const [pending, setPending] = useState(false);

    /**
     * When this browser first saw the incident. Only ever used to put a number in front of the
     * operator before they decide; the run's authoritative gap length is measured server side and
     * written to the gap sidecar, and a browser that joined mid-incident will read low here.
     */
    const incidentStartRef = useRef<number | null>(null);

    useEffect(() => {
        if (!incident) {
            incidentStartRef.current = null;
            setConfirming(false);
            setRefusal(null);
            setPending(false);
            return;
        }

        incidentStartRef.current ??= Date.now();
        setNow(Date.now());
        const timer = setInterval(() => setNow(Date.now()), COUNTDOWN_TICK_MS);
        return () => clearInterval(timer);
    }, [incident]);

    function send(command: () => Promise<CommandResponse>, label: string) {
        setPending(true);
        setRefusal(null);
        command()
            .then((response) => {
                if (!response?.accepted) {
                    setRefusal(response?.detail ?? `${label} was refused, with no reason given`);
                }
            })
            .catch((error: unknown) => {
                setRefusal(`${label} could not be sent: ${error instanceof Error ? error.message : String(error)}`);
            })
            .finally(() => setPending(false));
    }

    if (!incident || frame === null) {
        return null;
    }

    try {
        const gapMillis = now - (incidentStartRef.current ?? now);
        const holdRemaining = frame.safeHoldDeadlineMillis === null
            ? null
            : Math.max(0, frame.safeHoldDeadlineMillis - now);

        return (
            <div
                role="alert"
                style={{
                    background: 'var(--lumo-error-color, #e53935)',
                    color: 'var(--lumo-error-contrast-color, #fff)',
                    padding: 'var(--lumo-space-s) var(--lumo-space-m)',
                    borderRadius: '4px',
                }}
            >
                <strong style={{fontSize: '1.05em'}}>{headline(frame.state)}</strong>
                <p style={{margin: '0.35rem 0'}}>{frame.reason ?? 'the load cell stopped delivering measurements'}</p>
                <ul style={{margin: '0.35rem 0', paddingLeft: '1.2em'}}>
                    <li>
                        Last known force:{' '}
                        {frame.lastKnownForce === null
                            ? 'none — the run never produced a reading'
                            : `${(frame.lastKnownForce / 1000).toFixed(3)} kN`}
                    </li>
                    <li>Nothing has been measured for {formatDuration(gapMillis)}</li>
                    {holdRemaining !== null && (
                        <li>
                            {holdRemaining === 0
                                ? 'The hold has expired; the run is being aborted'
                                : `The run aborts itself in ${formatDuration(holdRemaining)}`}
                        </li>
                    )}
                    <li>Sensor losses this run: {frame.lossCount}; reconnect attempts: {frame.reconnectAttempt}</li>
                </ul>

                {refusal && (
                    <p style={{margin: '0.35rem 0', fontWeight: 600}}>The machine refused: {refusal}</p>
                )}

                <div style={{display: 'flex', gap: 'var(--lumo-space-s)', marginTop: 'var(--lumo-space-xs)'}}>
                    {/* Never disabled, not even while a command is in flight: ending the run is the one
                        control that must always answer. */}
                    <Button theme="primary error" onClick={() => send(TestRunnerService.abort, 'Abort')}>
                        Abort
                    </Button>
                    {/* disabled from the server's verdict alone — see TestRunnerThread#canResumeNow. */}
                    <Button
                        theme="primary"
                        disabled={!frame.canResume || pending}
                        onClick={() => setConfirming(true)}
                    >
                        Resume
                    </Button>
                </div>

                <ConfirmDialog
                    opened={confirming}
                    header="Resume this run?"
                    confirmText="Resume"
                    confirmTheme="primary error"
                    cancelButtonVisible
                    onConfirm={() => {
                        setConfirming(false);
                        send(TestRunnerService.resume, 'Resume');
                    }}
                    onCancel={() => setConfirming(false)}
                    onOpenedChanged={(event) => {
                        if (!event.detail.value) {
                            setConfirming(false);
                        }
                    }}
                >
                    <p>
                        Nothing was measured for {formatDuration(gapMillis)}. Resuming re-energizes the motor
                        and continues the run, but the force trace keeps that hole.
                    </p>
                    <p>
                        The result will be recorded as <strong>COMPLETED_WITH_GAPS</strong>. It is not comparable
                        with a run taken under continuous measurement, and any query that means &ldquo;clean
                        run&rdquo; will exclude it.
                    </p>
                </ConfirmDialog>
            </div>
        );
    } catch (error) {
        console.error('the incident banner failed to render', error);
        return (
            <p role="alert" style={{color: 'var(--lumo-error-text-color, #b71c1c)', fontWeight: 600}}>
                SENSOR LOST — the motor has been stopped. This banner could not render its detail; use the
                machine&apos;s own controls.
            </p>
        );
    }
}
