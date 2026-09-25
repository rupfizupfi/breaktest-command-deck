import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import React, {useEffect, useState} from "react";
import Plot from 'react-plotly.js';
import {TestResultService} from "Frontend/generated/endpoints";
import {HorizontalLayout, Select, VerticalLayout} from "@vaadin/react-components";
import {parseForceCsv} from "cms/components/dashboard/forceCsv";

interface ResultViewerProps {
    testResult: TestResult;
}

/**
 * The three states TestState#isIncident covers, as strings. Deliberately duplicated rather than
 * imported: the module dependency runs command-deck → cms and never back, and interruptionLog is a
 * plain column, so the parse has to be local. Keep in sync with TestState.
 */
const INCIDENT_STATES = ['SENSOR_LOST', 'SAFE_HOLD', 'RESUMING'];

const RUN_STATUS_COLOUR: Partial<Record<string, string>> = {
    COMPLETED_WITH_GAPS: '#a35b00',
    INTERRUPTED: '#a35b00',
    ABORTED: '#8a1f14',
    FAULT: '#8a1f14',
};

interface Gap {
    startMillis: number | null;
    durationMillis: number | null;
    outcome: string;
    /** Newton. Only ever set when a document carries it — see parseInterruptionLog. */
    driftNewton: number | null;
}

type InterruptionLog =
    | { kind: 'none' }
    | { kind: 'unreadable' }
    | { kind: 'ok', gaps: Gap[], gapCount: number | null };

/**
 * Reads the incident record off the result row.
 *
 * The column holds the transition document written by TestResultStatusPersister, so the gaps are
 * derived from it: an incident opens on the transition into SENSOR_LOST and closes on the first
 * transition back out of the incident states, whose target is the outcome. The per-gap force drift is
 * NOT in this column — it lives in the run's `_gaps.json` sidecar beside the force CSV, which is not
 * reachable from here — so it is rendered only if a document ever carries a `gaps` array itself.
 *
 * The parse is guarded because a run that died mid-write leaves the column truncated, and a missing
 * incident record is itself a finding: it must read as "unreadable", never blank the view.
 */
function parseInterruptionLog(raw?: string): InterruptionLog {
    if (!raw || raw.trim() === '') {
        return {kind: 'none'};
    }

    let doc: any;
    try {
        doc = JSON.parse(raw);
    } catch {
        return {kind: 'unreadable'};
    }

    if (doc === null || typeof doc !== 'object') {
        return {kind: 'unreadable'};
    }

    const gaps: Gap[] = [];
    if (Array.isArray(doc.gaps)) {
        for (const gap of doc.gaps) {
            gaps.push({
                startMillis: typeof gap?.startMillis === 'number' ? gap.startMillis : null,
                durationMillis: typeof gap?.durationMillis === 'number' ? gap.durationMillis : null,
                outcome: typeof gap?.outcome === 'string' ? gap.outcome : 'UNKNOWN',
                driftNewton: typeof gap?.driftNewton === 'number' ? gap.driftNewton : null,
            });
        }
    } else if (Array.isArray(doc.transitions)) {
        let open: any = null;
        for (const transition of doc.transitions) {
            const to = transition?.to;
            if (open === null) {
                if (to === 'SENSOR_LOST') {
                    open = transition;
                }
            } else if (!INCIDENT_STATES.includes(to)) {
                gaps.push({
                    startMillis: typeof open.at === 'number' ? open.at : null,
                    durationMillis: typeof open.at === 'number' && typeof transition.at === 'number'
                        ? transition.at - open.at : null,
                    outcome: typeof to === 'string' ? to : 'UNKNOWN',
                    driftNewton: null,
                });
                open = null;
            }
        }
        if (open !== null) {
            // No closing transition: the process died while the run was still holding.
            gaps.push({
                startMillis: typeof open.at === 'number' ? open.at : null,
                durationMillis: null,
                outcome: 'HOLDING',
                driftNewton: null,
            });
        }
    } else {
        return {kind: 'unreadable'};
    }

    return {kind: 'ok', gaps, gapCount: typeof doc.gapCount === 'number' ? doc.gapCount : null};
}

function formatMillis(millis: number | null): string {
    return millis === null ? 'unknown' : `${(millis / 1000).toFixed(1)} s`;
}

function formatInstant(millis: number | null): string {
    return millis === null ? 'unknown' : new Date(millis).toLocaleString();
}


/** The run's status and incident record, then a picker that charts one of its force CSVs. */
export default function ResultViewer({testResult}: ResultViewerProps): React.JSX.Element {
    const [dataFiles, setDataFiles] = useState<string[]>([]);
    const [selectedFile, setSelectedFile] = useState<string | undefined>();
    const [dataPoints, setDataPoints] = useState<[number[], number[]]>([[], []]);

    useEffect(() => {
        TestResultService.listCSVResults(testResult.id!).then(setDataFiles);
    }, []);

    useEffect(() => {
        if (selectedFile) {
            TestResultService.readCSVData(testResult.id!, selectedFile).then((text) => {
                const newPoints = parseForceCsv(text);

                const time = newPoints[0][0];
                newPoints[0] = newPoints[0].map(v=>v-time);
                setDataPoints(newPoints);
            });
        }
    }, [selectedFile]);

    let chart, info = null;
    if (dataPoints[0].length > 0) {
        const maxForce = Math.round(Math.max(...dataPoints[1])) / 1000;
        const minForce = Math.round(Math.min(...dataPoints[1])) / 1000;
        const duration = (dataPoints[0][dataPoints[0].length - 1] - dataPoints[0][0]) / 1000;
        info = (
            <HorizontalLayout theme="padding spacing-l stretch evenly">
                <h3 style={{width: '9em'}}>Duration: {duration} s</h3>
                <h3 style={{width: '9em'}}>Max Force: {maxForce} kN</h3>
                <h3 style={{width: '9em'}}>Min Force: {minForce} kN</h3>
            </HorizontalLayout>
        )

        chart = (<Plot
            data={[
                {
                    x: dataPoints[0],
                    y: dataPoints[1],
                    type: 'scatter',
                    mode: 'lines+markers',
                    marker: {color: 'blue'},
                },
            ]}
            layout={{
                width: 800,
                height: 400,
                title: selectedFile,
                xaxis: {
                    title: 'Time',
                    titlefont: {
                        family: 'Courier New, monospace',
                        size: 18,
                        color: '#7f7f7f'
                    }
                },
                yaxis: {
                    title: 'Force',
                    titlefont: {
                        family: 'Courier New, monospace',
                        size: 18,
                        color: '#7f7f7f'
                    }
                }
            }}/>);
    }

    const items = dataFiles.map((file) => ({label: file, value: file, disabled: false}));
    items.unshift({label: 'Select file', value: "", disabled: true});

    let testparamInfo;
    if (testResult.testParameter.type === 'cyclic') {
        testparamInfo = (
            <ul>
                <li>Upper turn force: {testResult.testParameter.upperTurnForce}</li>
                <li>Lower turn force: {testResult.testParameter.lowerTurnForce}</li>
            </ul>
        );
    } else {
        testparamInfo = (
            <ul>
                <li>Upper shut off threshold: {testResult.testParameter.upperShutOffThreshold}</li>
                <li>Lower shut off threshold: {testResult.testParameter.lowerShutOffThreshold}</li>
            </ul>
        );
    }

    const runStatus = testResult.runStatus;
    const interruptionLog = parseInterruptionLog(testResult.interruptionLog);

    let incidents = null;
    if (interruptionLog.kind === 'unreadable') {
        incidents = <p style={{color: '#8a1f14'}}>Incident log unreadable &mdash; the run may not have
            shut down cleanly. The gaps sidecar beside the force CSV is the fallback record.</p>;
    } else if (interruptionLog.kind === 'ok' && interruptionLog.gaps.length > 0) {
        incidents = (
            <>
                <p style={{color: '#a35b00', fontWeight: 600}}>
                    This run lost its load cell {interruptionLog.gaps.length === 1 ? 'once' : `${interruptionLog.gaps.length} times`}.
                    Nothing was measured during the intervals below, so the trace has a hole at each of them.
                </p>
                <ol>
                    {interruptionLog.gaps.map((gap, index) => (
                        <li key={index}>
                            {formatInstant(gap.startMillis)} &mdash; lasted {formatMillis(gap.durationMillis)},
                            ended as <strong>{gap.outcome}</strong>
                            {gap.driftNewton !== null && `, force drifted ${(gap.driftNewton / 1000).toFixed(3)} kN`}
                        </li>
                    ))}
                </ol>
            </>
        );
    }

    return (
        <VerticalLayout theme="padding spacing-l stretch evenly">
            <dl>
                <dfn>Test Parameter</dfn>
                <dd>{testResult.testParameter?.label}</dd>
                <dfn>Description</dfn>
                <dd>{testResult.description}</dd>
                <dfn>Run status</dfn>
                <dd style={{color: runStatus ? RUN_STATUS_COLOUR[runStatus] : undefined,
                    fontWeight: runStatus && RUN_STATUS_COLOUR[runStatus] ? 600 : undefined}}>
                    {/* Verbatim: this is the stored audit vocabulary, and COMPLETED_WITH_GAPS must not be
                        softened into something a reader can take for a clean run. */}
                    {runStatus ?? 'not recorded'}
                </dd>
                <dfn>Test parameters</dfn>
                <dd> {testparamInfo} </dd>
            </dl>
            {incidents}
            <HorizontalLayout theme="padding spacing-l stretch evenly">
                <label>Select data file:</label>
                <Select onChange={(e) => setSelectedFile(e.target.value)} items={items} value={selectedFile}/>
            </HorizontalLayout>
            {info}
            {chart}
        </VerticalLayout>
    );
}