import React, {useEffect, useState} from "react";
import {DeviceInfoService, SuckService} from "Frontend/generated/endpoints";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import {Checkbox} from "@vaadin/react-components";
import './InfoBoard.css';
import {Notification} from "@vaadin/react-components/Notification";

interface Info {
    id: number;
    speed: number;
    start: boolean;
    generalEnable: boolean;
    useSecondRamp: boolean;
    directionIsForward: boolean;
    motorCurrent: number;
    motorVoltage: number;
    motorTorque: number;
}

interface InfoBoardProps {
}

/**
 * This component is placed under the navigation bar and shows the current status of the system.
 */
export default function InfoBoard(props: InfoBoardProps): React.JSX.Element {
    const service = getService();
    const [info, setFCInfo] = useState<Info | null>(null);
    const [force, setForce] = useState<number>(0);
    const [enabled, setEnabled] = useState<boolean>(false);
    const [suckEnabled, setSuck] = useState<boolean>(false);

    useEffect(() => {
        if (!enabled) {
            return;
        }

        const subscription = service.loadCellObservable.subscribe({
            next: (value: IMessage) => {
                const newStatus: object[] = JSON.parse(value.body);
                // @ts-ignore
                setForce(newStatus[newStatus.length - 1].force);
            }
        });

        const infoSubscription = service.frequencyConverterInfoObservable.subscribe((value: IMessage) => {
            const newInfo: Info = JSON.parse(value.body);
            setFCInfo(newInfo);
        });

        DeviceInfoService.enable();
        service.connectComponent(InfoBoard);

        return () => {
            DeviceInfoService.disable();
            service.disconnectComponent(InfoBoard);
            subscription.unsubscribe();
            infoSubscription.unsubscribe();
        };
    }, [enabled]);

    const infoDom = info ? (
        <>
            <h3 className="lumo-typography">Status: {info.id}</h3>
            <ul className="info-list">
                <li className="info-item"><span>Speed:</span> <span>{info.speed * .375} mm/min</span></li>
                <li className="info-item"><span>Ramp:</span> <span>{info.useSecondRamp ? 'second' : 'first'}</span></li>
                <li className="info-item"><span>Direction:</span> <span>{info.directionIsForward ? 'push' : 'pull'}</span></li>
                <li className="info-item"><span>Motor current:</span> <span>{info.motorCurrent} A</span></li>
                <li className="info-item"><span>Motor voltage:</span> <span>{info.motorVoltage} V</span></li>
                <li className="info-item"><span>Motor torque:</span> <span>{info.motorTorque} Nm</span></li>
            </ul>
        </>
    ) : <div>is loading...</div>;

    return (
        <div className="info-board">
            <h2>Info:</h2>
            <ul className="info-list">
                <li className="info-item"><span>Force:</span> <span>{(force / 1000).toFixed(3)} Kn</span></li>
            </ul>
            {infoDom}
            <label>
                Show status: <Checkbox theme="primary" checked={enabled} onChange={function (e) {
                setEnabled(e.target.checked);
            }}/>
            </label>
            <br/>
            <label>
                Suck: <Checkbox theme="primary" checked={suckEnabled} onChange={function (e) {
                setSuck(e.target.checked);
                (e.target.checked ? SuckService.enable().then(confirm => {
                    if (confirm) {
                        Notification.show('it works')
                    } else {
                        Notification.show('action seems not possible')
                    }
                }) : SuckService.disable());
            }}/>
            </label>
        </div>
    );
}