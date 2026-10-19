import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import TestType from "Frontend/generated/ch/rupfizupfi/deck/data/TestType";
import React, {useEffect, useState} from "react";
import Plot from 'react-plotly.js';
import {TestResultService} from "Frontend/generated/endpoints";
import {HorizontalLayout, Select, VerticalLayout} from "@vaadin/react-components";
import {ForceTrace, parseForceCsv, toForceTrace} from "cms/components/dashboard/forceCsv";
import {RUN_FAULT_COLOUR, RUN_WARNING_COLOUR, RunStatusText} from "cms/model/runStatus";

interface ResultViewerProps {
    testResult: TestResult;
}

/**
 * The states TestState#isIncident covers. Duplicated because cms cannot depend on command-deck;
 * keep in sync with TestState.
 */
const INCIDENT_STATES = ['SENSOR_LOST', 'SAFE_HOLD', 'RESUMING'];

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
 * Derives gaps from TestResultStatusPersister's transition document: an incident opens on entering
 * SENSOR_LOST and closes on the first transition out of the incident states, whose target is the
 * outcome. Per-gap drift lives in the `_gaps.json` sidecar, not this column, so it shows only if the
 * document carries a `gaps` array.
 *
 * A run that died mid-write leaves truncated JSON; that must read as "unreadable", not blank the view.
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
    /** undefined while nothing is loaded, null for a file without a single sample. */
    const [trace, setTrace] = useState<ForceTrace | null | undefined>();
    const [loadError, setLoadError] = useState<string | null>(null);
    const testResultId = testResult.id!;

    useEffect(() => {
        let cancelled = false;
        setDataFiles([]);
        setSelectedFile(undefined);
        TestResultService.listCSVResults(testResultId).then((files) => {
            if (!cancelled) {
                setDataFiles((files ?? []).filter((file): file is string => file !== undefined));
            }
        }).catch(() => {
            if (!cancelled) {
                setLoadError('the list of data files could not be loaded');
            }
        });
        return () => {
            cancelled = true;
        };
    }, [testResultId]);

    useEffect(() => {
        setTrace(undefined);
        setLoadError(null);
        if (!selectedFile) {
            return;
        }

        // A slow response for a file the user already left must not overwrite the newer one.
        let cancelled = false;
        TestResultService.readCSVData(testResultId, selectedFile).then((text) => {
            if (!cancelled) {
                setTrace(toForceTrace(parseForceCsv(text ?? '')));
            }
        }).catch(() => {
            if (!cancelled) {
                setLoadError(`${selectedFile} could not be loaded`);
            }
        });
        return () => {
            cancelled = true;
        };
    }, [testResultId, selectedFile]);

    let chart, info = null;
    if (loadError !== null) {
        info = <p style={{color: 'var(--lumo-error-text-color)'}}>Error: {loadError}</p>;
    } else if (trace === null) {
        info = <p>{selectedFile} holds no samples.</p>;
    } else if (trace !== undefined) {
        info = (
            <HorizontalLayout theme="padding spacing-l stretch evenly">
                <h3 style={{width: '9em'}}>Duration: {trace.durationMillis / 1000} s</h3>
                <h3 style={{width: '9em'}}>Max Force: {Math.round(trace.maxForce) / 1000} kN</h3>
                <h3 style={{width: '9em'}}>Min Force: {Math.round(trace.minForce) / 1000} kN</h3>
            </HorizontalLayout>
        )

        // WebGL, lines only: SVG markers for every sample of a long run freeze the page.
        chart = (<Plot
            data={[
                {
                    x: trace.elapsedMillis,
                    y: trace.forces,
                    type: 'scattergl',
                    mode: 'lines',
                    line: {color: 'blue'},
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
    if (testResult.testParameter.type === TestType.CYCLIC) {
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
        incidents = <p style={{color: RUN_FAULT_COLOUR}}>Incident log unreadable &mdash; the run may not have
            shut down cleanly. The gaps sidecar beside the force CSV is the fallback record.</p>;
    } else if (interruptionLog.kind === 'ok' && interruptionLog.gaps.length > 0) {
        incidents = (
            <>
                <p style={{color: RUN_WARNING_COLOUR, fontWeight: 600}}>
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
                <dd><RunStatusText status={runStatus} missing="not recorded"/></dd>
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