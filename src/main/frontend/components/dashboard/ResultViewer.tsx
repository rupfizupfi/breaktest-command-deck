import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import React, {useEffect, useState} from "react";
import Plot from 'react-plotly.js';
import {TestResultService} from "Frontend/generated/endpoints";
import {HorizontalLayout, Select, VerticalLayout} from "@vaadin/react-components";

interface ResultViewerProps {
    testResult: TestResult;
}


/**
 * This component shows a dropdown for every data file for the testResult item.
 * If a file is selected then draw the graph for the data.
 */
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
                const lines = text.split('\n');
                const newPoints = lines.reduce((acc: [number[], number[]], line: string) => {
                    const [timestamp, force] = line.split(',').map(parseFloat);
                    acc[0].push(timestamp);
                    acc[1].push(force);
                    return acc;
                }, [[], []]);

                setDataPoints(newPoints);
            });
        }
    }, [selectedFile]);

    let chart, info = null;
    if (dataPoints[0].length > 0) {
        const maxForce = Math.round(Math.max(...dataPoints[1])) / 1000;
        const minForce = Math.round(Math.min(...dataPoints[1])) / 1000;
        const duration = dataPoints[0][dataPoints[0].length - 1] - dataPoints[0][0];
        info = (
            <VerticalLayout theme="padding spacing-l stretch evenly">
                <h3 style={{width: '8em'}}>Duration: {duration} kN</h3>
                <h3 style={{width: '8em'}}>Max Force: {maxForce} kN</h3>
                <h3 style={{width: '8em'}}>Min Force: {minForce} kN</h3>
            </VerticalLayout>
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

    let testparamInfo = null;
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

    return (
        <VerticalLayout theme="padding spacing-l stretch evenly">
            <dl>
                <dfn>Test Parameter</dfn>
                <dd>{testResult.testParameter?.label}</dd>
                <dfn>Description</dfn>
                <dd>{testResult.description}</dd>
                <dfn>Test parameters</dfn>
                <dd> {testparamInfo} </dd>
            </dl>
            <HorizontalLayout theme="padding spacing-l stretch evenly">
                <label>Select data file:</label>
                <Select onChange={(e) => setSelectedFile(e.target.value)} items={items} value={selectedFile}/>
            </HorizontalLayout>
            {info}
            {chart}
        </VerticalLayout>
    );
}