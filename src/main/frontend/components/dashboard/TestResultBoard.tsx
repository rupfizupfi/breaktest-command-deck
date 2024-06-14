import React, { useEffect, useState } from 'react';
import { Line } from 'react-chartjs-2';
import { getService } from "Frontend/service/StatusService";
import { IMessage } from "@stomp/rx-stomp";
import { Notification } from '@vaadin/react-components/Notification.js';
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import { TestRunnerService } from "Frontend/generated/endpoints";
//import 'chartjs-adapter-date-fns';
import { CategoryScale, Chart as ChartJS, Legend, LinearScale, LineElement, PointElement, TimeScale, TimeSeriesScale, Title, Tooltip } from 'chart.js';
import { Button } from "@vaadin/react-components/Button.js";
import { HorizontalLayout, VerticalLayout } from "@vaadin/react-components";

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


interface DataPoint {
    x: number;
    y: number;
}

interface TestResultBoardProps {
    testResult: TestResult;
    reset: () => void;
}

export default function TestResultBoard({ testResult, reset }: TestResultBoardProps): JSX.Element {
    const service = getService();
    const [dataPoints, setDataPoints] = useState<DataPoint[]>([]);

    useEffect(() => {
        const start = Date.now();
        const subscription = service.updateObservable.subscribe({
            next: (value: IMessage) => {
                const newStatus = JSON.parse(value.body);
                const newPoints = newStatus.map((item: any) => ({ x: item.timestamp-start, y: item.force }));
                setDataPoints(prevDataPoints => {
                    if(prevDataPoints.length>1000){
                        return [...prevDataPoints.slice(100), ...newPoints]
                    }
                    return [...prevDataPoints, ...newPoints]
                });
            }
        });
        const logSubscription = service.logObservable.subscribe((value: IMessage) => {
            console.log(value.body);
        })

        TestRunnerService.start(testResult.id!);
        service.connect();

        return () => {
            service.disconnect();
            subscription.unsubscribe();
            logSubscription.unsubscribe();
        };
    }, [testResult.id]);

    const yDataPoints = dataPoints.map((p)=>p.y);
    const data = {
        labels: dataPoints.map((p)=>p.x),
        datasets: [{
            label: 'Test Result',
            data: yDataPoints,
            fill: false,
            backgroundColor: 'rgb(255, 99, 132)',
            borderColor: 'rgba(255, 99, 132, 0.2)',
        }],
    };

    const options = {
        scales: {
            x: {
                title: {
                    display: true,
                    text: 'Time (Milliseconds)'
                },
            },
            y: {
                title: {
                    display: true,
                    text: 'Force'
                }
            }
        },
        responsive: true,
        maintainAspectRatio: false
    };

    const maxForce = yDataPoints.length ? Math.round(Math.max(...yDataPoints)) / 1000 : 0;
    const currentValue = dataPoints.length ? Math.round(dataPoints[dataPoints.length-1].y) / 1000: 0;

    return (
        <VerticalLayout className="w-full" theme="padding spacing-l stretch evenly" style={{ alignItems: 'stretch' }}>
            <HorizontalLayout className="w-full" theme="padding spacing-l stretch evenly">    
                <Button theme="primary" onClick={() => {
                    TestRunnerService.stop();
                    Notification.show('stopped');
                    reset();
                }}>Stop</Button>
                <h3 style={{ width: '8em' }}>Force: {currentValue} kN</h3>
                <h3 style={{ width: '8em' }}>Max: {maxForce} kN</h3>
            </HorizontalLayout>
            
            <div className="w-full">
                <Line data={data} options={options} />
            </div>
        </VerticalLayout>
    );
}
