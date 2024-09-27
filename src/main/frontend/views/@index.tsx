import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {useSignal} from '@vaadin/hilla-react-signals';
import {Button} from '@vaadin/react-components/Button.js';
import {Notification} from '@vaadin/react-components/Notification.js';
import {StreamControl} from 'Frontend/generated/endpoints.js';
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";

export const config: ViewConfig = {
    menu: {order: 0, icon: 'line-awesome/svg/globe-solid.svg'},
    title: 'Deck',
    loginRequired: true,
};

export default function DeckView() {
    const status = useSignal('');
    const service = getService();
    service.updateObservable.subscribe((value: IMessage) => status.value = value.body);

    return (
        <>
            <section className="p-m gap-m">
                <h2>Status: {status.value}</h2>
                <div className="flex p-m gap-m items-end">
                    <Button onClick={() => StreamControl.start().then((isStarted)=> {
                        if(isStarted){
                            Notification.show('started')
                            service.connect();
                        }
                        else {
                            Notification.show('already started')
                        }
                    })}>Start</Button>
                    <Button onClick={() => StreamControl.stop().then(()=> {
                        Notification.show('stopped')
                        service.disconnect();
                    })}>Stop</Button>
                </div>
            </section>
        </>
    );
}
