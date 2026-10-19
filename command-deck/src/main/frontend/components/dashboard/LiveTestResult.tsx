import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Line} from 'react-chartjs-2';
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import {Notification} from '@vaadin/react-components/Notification.js';
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import {TestRunnerService} from "Frontend/generated/endpoints";
import {
    Chart as ChartJS,
    ChartData,
    ChartOptions,
    Decimation,
    Legend,
    LinearScale,
    LineElement,
    Point,
    PointElement,
    Title,
    Tooltip
} from 'chart.js';
import {Button} from "@vaadin/react-components/Button.js";
import {ConfirmDialog} from '@vaadin/react-components/ConfirmDialog.js';
import {HorizontalLayout, VerticalLayout} from "@vaadin/react-components";
import LogComponent from "cms/components/dashboard/LogComponent";
import {appendLog, EMPTY_LOG, LogBuffer} from "cms/components/dashboard/logBuffer";
import ErrorBoundary from "cms/components/ErrorBoundary";
import {useLiveStatus} from "Frontend/service/useLiveStatus";
import {useConnectionState} from "Frontend/service/useTestState";
import StaleValue, {formatAge} from "Frontend/components/dashboard/StaleValue";
import TestIncidentBanner from "Frontend/components/dashboard/TestIncidentBanner";
import {createBuffer, ingest, resetBuffer, TraceBuffer} from "Frontend/components/dashboard/traceBuffer";
import {parseBatch} from "Frontend/service/loadCellBatch";

ChartJS.register(
    LinearScale,
    PointElement,
    LineElement,
    Title,
    Tooltip,
    Legend,
    Decimation,
);

export interface TestResultBoardProps {
    testResult?: TestResult;
    reset: () => void;
}

export default function LiveTestResult({testResult, reset}: TestResultBoardProps): React.JSX.Element {
    const [runningTestResult, setRunningTestResult] = useState<TestResult | null>(null);
    useEffect(() => {
        if (!testResult) {
            // Hilla types the result as possibly undefined.
            TestRunnerService.status().then((statusResponse) => {
                if (statusResponse?.isRunning && statusResponse.testResult) {
                    setRunningTestResult(statusResponse.testResult);
                }
            }).catch(() => {
                // A failed probe must not blank the view; the banner already shows the lost server.
            });
        } else {
            setRunningTestResult(null);
        }

        return () => {
            setRunningTestResult(null);
        }
    }, [testResult]);

    const activeTestResult = testResult ?? runningTestResult ?? undefined;

    if (!activeTestResult) {
        return <div>No test running</div>;
    }

    // Only an explicit Run hands a testResult in; a run found through status() is watched, never
    // started: a late start() would re-energize the motor for a run that just ended.
    return <TestResultGraph testResult={activeTestResult} reset={reset} startOnMount={testResult !== undefined}/>;
}

export interface TestResultGraphProps {
    testResult: TestResult;
    reset: () => void;
    /** True only for the view that issued Run; a watching view must never call start(). */
    startOnMount: boolean;
}

/** Readouts only have to look live to a human; the chart carries the detail. */
const READOUT_INTERVAL_MS = 250;

interface Readout {
    current: number;
    max: number;
    hasValue: boolean;
}

const NO_READING: Readout = {current: 0, max: 0, hasValue: false};

export function TestResultGraph({testResult, reset, startOnMount}: TestResultGraphProps): React.JSX.Element {
    const service = getService();
    // Allocated once and never replaced: the arrays inside are handed to Chart.js at mount.
    const bufferRef = useRef<TraceBuffer | null>(null);
    bufferRef.current ??= createBuffer();
    const buffer = bufferRef.current;
    const chartRef = useRef<ChartJS<'line', Point[]> | null>(null);
    const [readout, setReadout] = useState<Readout>(NO_READING);
    const [log, setLog] = useState<LogBuffer>(EMPTY_LOG);
    const [stopped, setStopped] = useState<boolean>(false);
    const [stopPending, setStopPending] = useState<boolean>(false);
    const [confirmingClose, setConfirmingClose] = useState<boolean>(false);
    const {loadCell} = useLiveStatus();
    const connection = useConnectionState();

    useEffect(() => {
        resetBuffer(buffer);
        setReadout(NO_READING);
        setLog(EMPTY_LOG);

        const subscription = service.loadCellObservable.subscribe({
            next: (value: IMessage) => {
                ingest(buffer, parseBatch(value.body));
                // 'none': the default 1 s animation would restart on every ~60 ms batch.
                chartRef.current?.update('none');
            }
        });

        const logSubscription = service.logObservable.subscribe((value: IMessage) => {
            setLog(previous => appendLog(previous, value.body));
        });

        // Returning `previous` unchanged skips the render, so a steady force costs nothing.
        const readoutTimer = setInterval(() => {
            setReadout(previous => {
                if (!buffer.hasValue) {
                    return previous;
                }
                return previous.hasValue && previous.current === buffer.last && previous.max === buffer.max
                    ? previous
                    : {current: buffer.last, max: buffer.max, hasValue: true};
            });
        }, READOUT_INTERVAL_MS);

        if (startOnMount) {
            // The rpc middleware toasts the failure; a run that never started has nothing to stop.
            TestRunnerService.start(testResult.id!).catch(() => setStopped(true));
        }
        service.connectComponent(TestResultGraph);

        return () => {
            service.disconnectComponent(TestResultGraph);
            subscription.unsubscribe();
            logSubscription.unsubscribe();
            clearInterval(readoutTimer);
        };
    }, [testResult.id, startOnMount]);

    const data = useMemo<ChartData<'line', Point[]>>(() => ({
        datasets: [{
            label: 'Test Result',
            data: buffer.points,
            fill: false,
            backgroundColor: 'rgb(255, 99, 132)',
            borderColor: 'rgba(255, 99, 132, 0.2)',
            showLine: true,
            // Must stay false so the NaN separators ingest() inserts break the line over holes.
            spanGaps: false
        }],
    }), []);

    // Stable identity: react-chartjs-2 re-runs an animated chart.update() when this or `data` changes.
    const options = useMemo<ChartOptions<'line'>>(() => ({
        animation: false,
        // The points are already {x, y}, and decimation is skipped while parsing is on.
        parsing: false,
        scales: {
            x: {
                // Decimation needs a linear (or time) x axis, {x, y} points and parsing disabled.
                type: 'linear',
                title: {
                    display: true,
                    text: 'Time since this view opened (milliseconds)'
                },
                min: 0,
            },
            y: {
                title: {
                    display: true,
                    text: 'Force'
                },
                // No fixed max: a fixed range hid peaks above it, and the 150 kN cell spans far more.
                min: 0
            }
        },
        plugins: {
            // min-max, not lttb: the peak force is the measurement. Past 4x the canvas width each x
            // bucket keeps only its first, last, min and max, so a NaN separator survives only as a
            // bucket end; the readouts come off the buffer - doc/dev/frontend/state-and-realtime.md.
            decimation: {enabled: true, algorithm: 'min-max'}
        },
        responsive: true,
        maintainAspectRatio: false
    }), []);

    // Reported only once the server took it; a failed Stop stays armed and says so, because the
    // operator must not walk away believing the motor is off.
    async function sendStop() {
        setStopPending(true);
        try {
            await TestRunnerService.stop();
            Notification.show('stop sent to the machine');
            setStopped(true);
        } catch {
            Notification.show('STOP DID NOT REACH THE MACHINE - press Stop again or use the physical E-stop',
                {theme: 'error', duration: 0, position: 'middle'});
        } finally {
            setStopPending(false);
        }
    }

    async function stopThisRunAndClose() {
        setStopPending(true);
        try {
            // Scoped to this run: a stale view must never stop a newer one.
            await TestRunnerService.stopRun(testResult.id!);
            setStopped(true);
            reset();
        } catch {
            Notification.show('the stop did not reach the machine, the view stays open',
                {theme: 'error', duration: 0, position: 'middle'});
        } finally {
            setStopPending(false);
        }
    }

    const maxForce = readout.hasValue ? Math.round(readout.max) / 1000 : 0;
    const currentValue = readout.hasValue ? Math.round(readout.current) / 1000 : 0;

    return (
        <VerticalLayout className="w-full" theme="padding spacing-l stretch evenly" style={{alignItems: 'stretch'}}>
            <HorizontalLayout className="w-full" theme="padding spacing-l stretch evenly">
                <Button theme="primary" disabled={stopPending} onClick={() => void sendStop()}>
                    {stopPending ? 'Stopping…' : 'Stop'}
                </Button>
                <Button theme="primary error" disabled={stopPending} onClick={() => {
                    if (stopped) {
                        reset();
                    } else {
                        setConfirmingClose(true);
                    }
                }}>Close</Button>
                <ConfirmDialog
                    opened={confirmingClose}
                    header={`Stop run ${testResult.id} and close?`}
                    confirmText="Stop and close"
                    confirmTheme="primary error"
                    cancelButtonVisible
                    onConfirm={() => {
                        setConfirmingClose(false);
                        void stopThisRunAndClose();
                    }}
                    onCancel={() => setConfirmingClose(false)}
                    onOpenedChanged={(event) => {
                        if (!event.detail.value) {
                            setConfirmingClose(false);
                        }
                    }}
                >
                    <p>This run is still active. Closing stops it; only this run is stopped.</p>
                </ConfirmDialog>
                <h3 style={{minWidth: '8em'}}>Force: <StaleValue status={loadCell} hasValue={readout.hasValue}>{currentValue} kN</StaleValue></h3>
                <h3 style={{minWidth: '8em'}}>Max: {maxForce} kN</h3>
            </HorizontalLayout>

            {/* One ConnectionState, so the warnings are mutually exclusive: a sensor loss is red and
                actionable, a browser-side connection problem stays amber. */}
            <TestIncidentBanner/>
            {connection.kind === 'server-lost' && (
                <p className="feed-warning">
                    no connection to the machine &mdash; this view is not live
                </p>
            )}
            {connection.kind === 'feed-stale' && (
                <p className="feed-warning">
                    no force data for {formatAge(connection.seconds)} &mdash; the chart has stopped
                    advancing and the force above is not live
                </p>
            )}

            {/* Its own boundary: a chart crash must leave Stop and the incident banner above it. */}
            <ErrorBoundary region="The live chart" resetKey={testResult.id}>
                <div className="w-full">
                    <Line ref={chartRef} data={data} options={options}/>
                </div>
                <LogComponent log={log}/>
            </ErrorBoundary>
        </VerticalLayout>
    );
}
