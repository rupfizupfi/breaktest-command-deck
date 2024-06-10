import React, {JSX, useEffect, useState} from 'react';
import {Line} from 'react-chartjs-2';
import {useSignal} from "@vaadin/hilla-react-signals";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import {TestRunnerService} from "Frontend/generated/endpoints";
import 'chartjs-adapter-date-fns';


import {CategoryScale, Chart as ChartJS, Legend, LinearScale, LineElement, PointElement, TimeScale, Title, Tooltip} from 'chart.js';
import {Button} from "@vaadin/react-components/Button.js";
import {VerticalLayout} from "@vaadin/react-components";

ChartJS.register(
    CategoryScale,
    LinearScale,
    TimeScale,
    PointElement,
    LineElement,
    Title,
    Tooltip,
    Legend,
);


type DataPoint = {
    x: number;
    y: number;
}

interface TestResultBoardProps {
    testResult: TestResult;
    reset: () => void;
}

export default function TestResultBoard({testResult, reset}: TestResultBoardProps): JSX.Element {
    const status = useSignal({timestamp: 0, force: 0});
    const service = getService();
    const [dataPoints, setDataPoints] = useState<DataPoint[]>([]);

    service.updateObservable.subscribe((value: IMessage) => {
        const newStatus = JSON.parse(value.body);
        status.value = newStatus;
        setDataPoints(prevDataPoints => [...prevDataPoints, {x: newStatus.timestamp, y: newStatus.force}]);
    });

    useEffect(() => {
        TestRunnerService.start(testResult.id!);
    }, []);

    const data = {
        datasets: [
            {
                label: 'Test Result',
                data: dataPoints,
                fill: false,
                backgroundColor: 'rgb(255, 99, 132)',
                borderColor: 'rgba(255, 99, 132, 0.2)',
            },
        ],
    };

    const options = {
        scales: {
            x: {
                time: {
                    tooltipFormat: 'ss'
                },
                title: {
                    display: true,
                    text: 'seconds'
                }
            },
            y: {
                title: {
                    display: true,
                    text: 'force'
                }
            }
        }
    };

    return  <VerticalLayout  className="w-full" theme="padding spacing-l stretch evenly"  style={{ alignItems: 'stretch' }}>
        <Button theme="primary" onClick={() => {
            TestRunnerService.stop();
            reset();
        }}>Stop</Button>
        <div className="w-full">
            <Line data={data} options={options}/>
        </div>
    </VerticalLayout>;
}