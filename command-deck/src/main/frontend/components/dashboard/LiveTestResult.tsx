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

    if (!(testResult || runningTestResult)) {
        return <div>No test running</div>;
    }

    // @ts-ignore
    return <TestResultGraph testResult={testResult || runningTestResult} reset={reset}/>;
}

export interface TestResultGraphProps {
    testResult: TestResult;
    reset: () => void;
}

/** One `/topic/load-cell` element: server-clock `System.currentTimeMillis()` and newtons. */
interface ForceSample {
    timestamp: number;
    force: number;
}

/**
 * Trace capacity, ~11 minutes at the DSCUSB's rated 200 samples/s. Raising it is paid twice: ~40
 * bytes per point held for the run, and a full min-max decimation rescan on each of the ~16 frames
 * a second the broadcaster sends. Eviction never moves the headline number — the running max is
 * kept separately and covers the whole run, not the visible window.
 */
const MAX_SAMPLES = 131_072;

/** Evict a block per overflow, not a point per sample: one O(n) splice per EVICT_BLOCK ingests. */
const EVICT_BLOCK = 8_192;

/**
 * How far behind a batch's newest sample a sample may sit and still belong to this run. The opening
 * batch can lead with the *previous* run's stranded tail, and anchoring the x axis on one of those
 * pushes the whole trace off screen. Sized between the ~60 ms an in-run batch spans and the gap to
 * a previous run, which is at least the operator's time to start it.
 * See doc/06-feature-work/testrunner-safety/staleness-and-lifecycle-findings.md.
 */
const STRANDED_TAIL_MS = 500;

/** Readouts only have to look live to a human; the chart carries the detail. */
const READOUT_INTERVAL_MS = 250;

interface TraceBuffer {
    /** Chronological, capped at MAX_SAMPLES. Chart.js holds this reference — never replace it. */
    points: Point[];
    /** Objects freed by the last eviction, refilled in place so a long run stops allocating. */
    recycled: Point[];
    /** First accepted sample's server timestamp; the x origin. Null until the first sample. */
    anchor: number | null;
    max: number;
    last: number;
    hasValue: boolean;
}

interface Readout {
    current: number;
    max: number;
    hasValue: boolean;
}

const NO_READING: Readout = {current: 0, max: 0, hasValue: false};

function createBuffer(): TraceBuffer {
    return {points: [], recycled: [], anchor: null, max: Number.NEGATIVE_INFINITY, last: 0, hasValue: false};
}

function resetBuffer(buffer: TraceBuffer): void {
    // In place: Chart.js holds `points` by reference, so replacing the array would orphan the chart.
    buffer.points.length = 0;
    buffer.recycled.length = 0;
    buffer.anchor = null;
    buffer.max = Number.NEGATIVE_INFINITY;
    buffer.last = 0;
    buffer.hasValue = false;
}

function append(buffer: TraceBuffer, x: number, y: number): void {
    if (buffer.points.length >= MAX_SAMPLES) {
        buffer.recycled = buffer.points.splice(0, EVICT_BLOCK);
    }

    const reused = buffer.recycled.pop();
    if (reused) {
        reused.x = x;
        reused.y = y;
        buffer.points.push(reused);
    } else {
        buffer.points.push({x, y});
    }

    if (y > buffer.max) {
        buffer.max = y;
    }
    buffer.last = y;
    buffer.hasValue = true;
}

/** Index of the first sample of this run, or -1 when the batch is entirely a stranded tail. */
function firstOwnSample(batch: ForceSample[]): number {
    const newest = batch[batch.length - 1].timestamp;
    for (let i = 0; i < batch.length; i++) {
        if (newest - batch[i].timestamp <= STRANDED_TAIL_MS) {
            return i;
        }
    }
    return -1;
}

function ingest(buffer: TraceBuffer, batch: ForceSample[]): void {
    if (batch.length === 0) {
        return;
    }

    let from = 0;
    if (buffer.anchor === null) {
        from = firstOwnSample(batch);
        if (from < 0) {
            return;
        }
        buffer.anchor = batch[from].timestamp;
    }

    // Index loop, never spread: the batch is unbounded from this component's point of view, and
    // `push(...batch)` throws RangeError once it is large enough to matter.
    for (let i = from; i < batch.length; i++) {
        append(buffer, batch[i].timestamp - buffer.anchor, batch[i].force);
    }
}

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
                ingest(buffer, JSON.parse(value.body) as ForceSample[]);
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

        TestRunnerService.start(testResult.id!);
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
            showLine: false,
            // false, and it must stay false: a line spanning a gap runs straight through an interval
            // where nothing was measured, which is precisely the misreading the recovery feature
            // exists to prevent. Necessary but not sufficient - Chart.js only breaks a line at a
            // null/NaN y, and ingest() never inserts one, so a sensor loss currently shows as a jump
            // in x with no marker. Today nothing is drawn across it because showLine is false; both
            // of those have to change together.
            spanGaps: false
        }],
    }), []);

    // Stable identity, and not only for render cost: react-chartjs-2 re-runs an animated
    // chart.update() whenever this object or `data` changes identity.
    const options = useMemo<ChartOptions<'line'>>(() => ({
        animation: false,
        scales: {
            x: {
                // Decimation needs a linear (or time) x axis and {x, y} points; `parsing` stays unset.
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
            // min-max, not lttb: on a destructive test the peak force is the measurement, and lttb
            // is free to drop it.
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
