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
import {HorizontalLayout, VerticalLayout} from "@vaadin/react-components";
import LogComponent from "cms/components/dashboard/LogComponent";
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
            // Optional-chained because Hilla types an endpoint result as possibly undefined: the
            // call resolves to nothing if the endpoint is unavailable. Dev mode's own typecheck
            // flags a bare property access here even when the committed client happens not to.
            TestRunnerService.status().then((statusResponse) => {
                if (statusResponse?.isRunning && statusResponse.testResult) {
                    setRunningTestResult(statusResponse.testResult);
                }
            }).catch(() => {
                // A status probe that fails must not blank the live view; the banner and the
                // staleness indicator already annunciate a server that is not answering.
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

    return <TestResultGraph testResult={activeTestResult} reset={reset}/>;
}

export interface TestResultGraphProps {
    testResult: TestResult;
    reset: () => void;
}

/** Readouts only have to look live to a human; the chart carries the detail. */
const READOUT_INTERVAL_MS = 250;

interface Readout {
    current: number;
    max: number;
    hasValue: boolean;
}

const NO_READING: Readout = {current: 0, max: 0, hasValue: false};

export function TestResultGraph({testResult, reset}: TestResultGraphProps): React.JSX.Element {
    const service = getService();
    // Allocated once and never replaced: the arrays inside are handed to Chart.js at mount.
    const bufferRef = useRef<TraceBuffer | null>(null);
    bufferRef.current ??= createBuffer();
    const buffer = bufferRef.current;
    const chartRef = useRef<ChartJS<'line', Point[]> | null>(null);
    const [readout, setReadout] = useState<Readout>(NO_READING);
    const [logs, setLogs] = useState<string[]>([]);
    const [stopped, setStopped] = useState<boolean>(false);
    const {loadCell} = useLiveStatus();
    const connection = useConnectionState();

    useEffect(() => {
        resetBuffer(buffer);
        setReadout(NO_READING);

        const subscription = service.loadCellObservable.subscribe({
            next: (value: IMessage) => {
                ingest(buffer, parseBatch(value.body));
                // 'none': the default mode animates over 1 s, and the next batch lands ~60 ms later,
                // so every frame would restart an interpolation that never finishes.
                chartRef.current?.update('none');
            }
        });

        const logSubscription = service.logObservable.subscribe((value: IMessage) => {
            setLogs(prevLogs => [...prevLogs, value.body]);
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

        // The rpc middleware toasts the failure; a run that never started has nothing to stop.
        TestRunnerService.start(testResult.id!).catch(() => setStopped(true));
        service.connectComponent(TestResultGraph);

        return () => {
            service.disconnectComponent(TestResultGraph);
            subscription.unsubscribe();
            logSubscription.unsubscribe();
            clearInterval(readoutTimer);
        };
    }, [testResult.id]);

    const data = useMemo<ChartData<'line', Point[]>>(() => ({
        datasets: [{
            label: 'Test Result',
            data: buffer.points,
            fill: false,
            backgroundColor: 'rgb(255, 99, 132)',
            borderColor: 'rgba(255, 99, 132, 0.2)',
            showLine: true,
            // false, and it must stay false: the line is broken by the NaN separator ingest() puts
            // in a hole longer than GAP_MS, and nothing is then drawn across an interval where
            // nothing was measured.
            spanGaps: false
        }],
    }), []);

    // Stable identity, and not only for render cost: react-chartjs-2 re-runs an animated
    // chart.update() whenever this object or `data` changes identity.
    const options = useMemo<ChartOptions<'line'>>(() => ({
        animation: false,
        // The datasets are already {x, y} TracePoints, and Chart.js returns from the decimation
        // plugin before decimating whenever parsing is on.
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
                min: 0,
                max: 40000
            }
        },
        plugins: {
            // min-max, not lttb: the peak force is the measurement. Past 4x the canvas width each x
            // bucket keeps only its first, last, min and max, so a NaN separator survives only as a
            // bucket end; the readouts come off the buffer - doc/04-frontend/state-and-realtime.md.
            decimation: {enabled: true, algorithm: 'min-max'}
        },
        responsive: true,
        maintainAspectRatio: false
    }), []);

    const maxForce = readout.hasValue ? Math.round(readout.max) / 1000 : 0;
    const currentValue = readout.hasValue ? Math.round(readout.current) / 1000 : 0;

    return (
        <VerticalLayout className="w-full" theme="padding spacing-l stretch evenly" style={{alignItems: 'stretch'}}>
            <HorizontalLayout className="w-full" theme="padding spacing-l stretch evenly">
                <Button theme="primary" onClick={() => {
                    TestRunnerService.stop();
                    Notification.show('stopped');
                    setStopped(true);
                }}>Stop</Button>
                <Button theme="primary error" onClick={() => {
                    if (!stopped) {
                        TestRunnerService.stop();
                        Notification.show('stopped');
                    }
                    reset();
                }}>Close</Button>
                <h3 style={{minWidth: '8em'}}>Force: <StaleValue status={loadCell} hasValue={readout.hasValue}>{currentValue} kN</StaleValue></h3>
                <h3 style={{minWidth: '8em'}}>Max: {maxForce} kN</h3>
            </HorizontalLayout>

            {/* One source, so the three warnings are mutually exclusive by construction: a sensor loss
                is red and actionable, a browser-side connection problem stays amber. Both used to
                render as staleness text, which read the machine's fault as the display's. */}
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

            <div className="w-full">
                <Line ref={chartRef} data={data} options={options}/>
            </div>
            <LogComponent logs={logs}/>
        </VerticalLayout>
    );
}
