import React, {useEffect, useRef, useState} from "react";
import {DeviceInfoService, SuckService} from "Frontend/generated/endpoints";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import {Checkbox} from "@vaadin/react-components";
import './InfoBoard.css';
import {Notification} from "@vaadin/react-components/Notification";
import {useLiveStatus} from "Frontend/service/useLiveStatus";
import StaleValue, {formatAge} from "Frontend/components/dashboard/StaleValue";
import {latestForce, parseBatch} from "Frontend/service/loadCellBatch";

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
    // null rather than 0: an initial zero is indistinguishable from a genuine "no load" reading.
    const [force, setForce] = useState<number | null>(null);
    const [enabled, setEnabled] = useState<boolean>(false);
    const [suckEnabled, setSuck] = useState<boolean>(false);
    const {loadCell, frequencyInverter, connected} = useLiveStatus();

    useEffect(() => {
        if (!enabled) {
            return;
        }

        const subscription = service.loadCellObservable.subscribe({
            next: (value: IMessage) => {
                const latest = latestForce(parseBatch(value.body));
                if (latest !== undefined) {
                    setForce(latest);
                }
            }
        });

        const infoSubscription = service.frequencyInverterInfoObservable.subscribe((value: IMessage) => {
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

    const suckSeeded = useRef(false);

    useEffect(() => {
        // Mount, and every socket re-open. Not on the way down: that call would go to the server the
        // socket just lost, and rpcErrorPolicy would toast it on every reconnect cycle.
        if (!connected && suckSeeded.current) {
            return;
        }
        suckSeeded.current = true;

        let cancelled = false;
        const read = () => SuckService.isEnabled().then(on => {
            if (!cancelled) {
                setSuck(on);
            }
        });

        read();
        // SuckJob switches the relay seconds after a run finishes, so only a poll keeps the checkbox
        // showing what the service holds rather than the outcome of the last click here.
        const poll = connected ? window.setInterval(read, 2000) : undefined;

        return () => {
            cancelled = true;
            window.clearInterval(poll);
        };
    }, [connected]);

    // The backend only publishes inverter info while broadcasting is enabled, so silence is only
    // meaningful once we have asked for it.
    const inverterStale = enabled && info !== null && frequencyInverter.stale;

    const infoDom = info ? (
        <>
            <h3 className="lumo-typography">Status: {info.id}</h3>
            {inverterStale && (
                <p className="feed-warning">
                    no update for {formatAge(frequencyInverter.staleForSeconds)} &mdash; the values below are not live
                </p>
            )}
            <ul className={inverterStale ? "info-list feed-values--stale" : "info-list"}>
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
            {enabled && !connected && (
                <p className="feed-warning feed-warning--disconnected">
                    no connection to the machine &mdash; nothing on this panel is live
                </p>
            )}
            <ul className="info-list">
                <li className="info-item">
                    <span>Force:</span>
                    <span>
                        <StaleValue status={loadCell} hasValue={force !== null}>
                            {((force ?? 0) / 1000).toFixed(3)} kN
                        </StaleValue>
                    </span>
                </li>
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
                // The backend's relay state is the only truth about the vacuum, and React rewrites
                // the element only on a state change, so the outcome goes to both.
                const checkbox = e.target;
                if (checkbox.checked) {
                    SuckService.enable().then(energized => {
                        setSuck(energized);
                        checkbox.checked = energized;
                        if (!energized) {
                            Notification.show('Vacuum could not be switched on - relay port not found or not writable');
                        }
                    }).catch(() => {
                        checkbox.checked = suckEnabled;
                    });
                } else {
                    SuckService.disable().then(off => {
                        setSuck(!off);
                        checkbox.checked = !off;
                        if (!off) {
                            Notification.show('Vacuum could not be switched off - check the relay');
                        }
                    }).catch(() => {
                        checkbox.checked = suckEnabled;
                    });
                }
            }}/>
            </label>
        </div>
    );
}