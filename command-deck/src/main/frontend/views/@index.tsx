import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {Button} from '@vaadin/react-components/Button.js';
import {Notification} from '@vaadin/react-components/Notification.js';
import { DeviceInfoService } from "Frontend/generated/endpoints";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import {useEffect, useRef, useState} from "react";
import {latestForce, parseBatch} from "Frontend/service/loadCellBatch";
import {useLiveStatus} from "Frontend/service/useLiveStatus";
import StaleValue from "Frontend/components/dashboard/StaleValue";
import {infoClientId} from "Frontend/service/infoClientId";

export const config: ViewConfig = {
    menu: {order: 0, icon: 'line-awesome/svg/globe-solid.svg'},
    title: 'Deck',
    loginRequired: true,
};

/** Batches arrive at ~16 Hz; a human reads the number at a quarter of that. */
const READOUT_INTERVAL_MS = 250;

export default function DeckView() {
    const service = getService();
    const {loadCell} = useLiveStatus();
    // null, not 0: an initial zero would look like a genuine "no load" reading.
    const [force, setForce] = useState<number | null>(null);
    const latestRef = useRef<number | null>(null);

    useEffect(() => {
        const subscription = service.loadCellObservable.subscribe((value: IMessage) => {
            const latest = latestForce(parseBatch(value.body));
            if (latest !== undefined) {
                latestRef.current = latest;
            }
        });
        const readoutTimer = setInterval(() => setForce(latestRef.current), READOUT_INTERVAL_MS);
        service.connectComponent(DeckView);

        return () => {
            service.disconnectComponent(DeckView);
            subscription.unsubscribe();
            clearInterval(readoutTimer);
        };
    }, [service]);

    // Start/Stop take and release this view's hold on the server's broadcasting only; InfoBoard
    // and other tabs hold their own. The socket belongs to the refcount: closing it here would
    // blind InfoBoard and a run view still showing a turning motor.
    // The rpc middleware toasts a failed call.
    return (
        <>
            <section className="p-m gap-m">
                <h2>
                    Force:{' '}
                    <StaleValue status={loadCell} hasValue={force !== null}>
                        {((force ?? 0) / 1000).toFixed(3)} kN
                    </StaleValue>
                </h2>
                <div className="flex p-m gap-m items-end">
                    <Button onClick={() => DeviceInfoService.enable(infoClientId('deck-view')).then((isStarted) => {
                        Notification.show(isStarted ? 'started' : 'already started here');
                    }).catch(() => {})}>Start</Button>
                    <Button onClick={() => DeviceInfoService.disable(infoClientId('deck-view')).then((wasHeld) => {
                        Notification.show(wasHeld ? 'released' : 'not started here');
                    }).catch(() => {})}>Stop</Button>
                </div>
            </section>
        </>
    );
}
