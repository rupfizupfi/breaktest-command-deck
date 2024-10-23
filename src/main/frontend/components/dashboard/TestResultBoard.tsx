import React, {useEffect, useState} from 'react';
import {Line} from 'react-chartjs-2';
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import {Notification} from '@vaadin/react-components/Notification.js';
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import {TestRunnerService} from "Frontend/generated/endpoints";
//import 'chartjs-adapter-date-fns';
import {CategoryScale, Chart as ChartJS, Legend, LinearScale, LineElement, PointElement, TimeScale, TimeSeriesScale, Title, Tooltip} from 'chart.js';
import {Button} from "@vaadin/react-components/Button.js";
import {HorizontalLayout, VerticalLayout} from "@vaadin/react-components";
import LogComponent from "Frontend/components/dashboard/LogComponent";

ChartJS.register(
    CategoryScale,
    LinearScale,
    TimeScale,
    TimeSeriesScale,
    PointElement,
    LineElement,
    Title,
    Tooltip,
    Legend,
);

interface TestResultBoardProps {
    testResult: TestResult;
    reset: () => void;
}

export default function TestResultBoard({testResult, reset}: TestResultBoardProps): React.JSX.Element {
    const service = getService();
    const [dataPoints, setDataPoints] = useState<[number[], number[]]>([[], []]);
    const [logs, setLogs] = useState<string[]>([]);
    const [stopped, setStopped] = useState<boolean>(false);

    useEffect(() => {
        const start = Date.now();
        const subscription = service.loadCellObservable.subscribe({
            next: (value: IMessage) => {
                const newStatus = JSON.parse(value.body);
                const newPoints = newStatus.reduce((acc: [number[], number[]], item: any) => {
                    acc[0].push(item.timestamp - start);
                    acc[1].push(item.force);
                    return acc;
                }, [[], []]);

                setDataPoints(prevDataPoints => {
                    // if(prevDataPoints.length>1000000){
                    //     return [...prevDataPoints.slice(1000), ...newPoints]
                    // }
                    prevDataPoints[0].push(...newPoints[0]);
                    prevDataPoints[1].push(...newPoints[1])
                    return [prevDataPoints[0], prevDataPoints[1]];
                });
            }
        });

        const logSubscription = service.logObservable.subscribe((value: IMessage) => {
            setLogs(prevLogs => [...prevLogs, value.body]);
        });

        TestRunnerService.start(testResult.id!);
        service.connect();

        return () => {
            service.disconnect();
            subscription.unsubscribe();
            logSubscription.unsubscribe();
        };
    }, [testResult.id]);

    const yDataPoints = dataPoints[1];
    const data = {
        labels: dataPoints[0],
        datasets: [{
            label: 'Test Result',
            data: yDataPoints,
            fill: false,
            backgroundColor: 'rgb(255, 99, 132)',
            borderColor: 'rgba(255, 99, 132, 0.2)',
            showLine: false,
            spanGaps: true
        }],
    };

    const options = {
        scales: {
            x: {
                title: {
                    display: true,
                    text: 'Time (Milliseconds)'
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
        responsive: true,
        maintainAspectRatio: false
    };

    const maxForce = yDataPoints.length ? Math.round(Math.max(...yDataPoints)) / 1000 : 0;
    const currentValue = dataPoints.length ? Math.round(yDataPoints[yDataPoints.length - 1]) / 1000 : 0;

    return (
        <VerticalLayout className="w-full" theme="padding spacing-l stretch evenly" style={{alignItems: 'stretch'}}>
            <HorizontalLayout className="w-full" theme="padding spacing-l stretch evenly">
                <Button theme="primary" onClick={() => {
                    TestRunnerService.stop();
                    Notification.show('stopped');
                    setStopped(true);
                }}>Stop</Button>
                <Button theme="primary error" onClick={() => {
                    if(!stopped){
                        TestRunnerService.stop();
                        Notification.show('stopped');
                    }
                    reset();
                }}>Close</Button>
                <h3 style={{width: '8em'}}>Force: {currentValue} kN</h3>
                <h3 style={{width: '8em'}}>Max: {maxForce} kN</h3>
            </HorizontalLayout>

            <div className="w-full">
                <Line data={data} options={options}/>
            </div>
            <LogComponent logs={logs}/>
        </VerticalLayout>
    );
}
