import React, { useEffect, useState } from "react";
import { DeviceInfoService } from "Frontend/generated/endpoints";
import { getService } from "Frontend/service/StatusService";
import { IMessage } from "@stomp/rx-stomp";
import { Checkbox } from "@vaadin/react-components";
import './InfoBoard.css';

interface Info {
    speed: number;
    start: boolean;
    generalEnable: boolean;
    useSecondRamp: boolean;
    directionIsForward: boolean;
}

interface InfoBoardProps {}

/**
 * This component is placed under the navigation bar and shows the current status of the system.
 */
export default function InfoBoard(props: InfoBoardProps): React.JSX.Element {
    const service = getService();
    const [info, setFCInfo] = useState<Info | null>(null);
    const [force, setForce] = useState<number>(0);
    const [enabled, setEnabled] = useState<boolean>(false);

    useEffect(() => {
        if (!enabled) {
            return;
        }

        const subscription = service.updateObservable.subscribe({
            next: (value: IMessage) => {
                const newStatus: object[] = JSON.parse(value.body);
                // @ts-ignore
                setForce(newStatus[newStatus.length - 1].force);
            }
        });

        const infoSubscription = service.logObservable.subscribe((value: IMessage) => {
            const newInfo: Info = JSON.parse(value.body);
            setFCInfo(newInfo);
        });

        DeviceInfoService.enable();
        service.connect();

        return () => {
            DeviceInfoService.disable();
            service.disconnect();
            subscription.unsubscribe();
            infoSubscription.unsubscribe();
        };
    }, [enabled]);

    const infoDom = info ? (
        <>
            <h3 className="lumo-typography">Status:</h3>
            <ul className="info-list">
                <li className="info-item"><span>Speed:</span> <span>{info.speed}</span></li>
                <li className="info-item"><span>Ramp:</span> <span>{info.useSecondRamp ? 'second' : 'first'}</span></li>
                <li className="info-item"><span>Direction:</span> <span>{info.directionIsForward ? 'forwards' : 'backwards'}</span></li>
            </ul>
        </>
    ) : <div>is loading...</div>;

    return (
        <div className="info-board">
            <h2>Info:</h2>
            <ul className="info-list">
                <li className="info-item"><span>Force:</span> <span>{force}</span></li>
            </ul>
            {infoDom}
            <label>
                Show status: <Checkbox checked={enabled} onChange={function (e) {
                setEnabled(e.target.checked);
            }}/>
            </label>
        </div>
    );
}