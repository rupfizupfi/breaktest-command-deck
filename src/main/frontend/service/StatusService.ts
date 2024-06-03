import {IFrame, IMessage, RxStomp} from "@stomp/rx-stomp";
import {Observable} from "rxjs/internal/Observable";

export default class StatusService {
    private rxStomp: RxStomp;
    private updateTopic: Observable<IMessage>;

    constructor() {
        this.rxStomp = new RxStomp();
        this.rxStomp.configure({
            brokerURL: 'ws://localhost:8080/status',
        });

        this.updateTopic = this.rxStomp
            .watch({destination: "/topic/updates"});

        // this.updateTopic.subscribe((message: IMessage) => {
        //     console.log("Received message: " + message.body);
        // });

        this.rxStomp.stompErrors$.subscribe((frame: IFrame) => {
            console.error('Broker reported error: ' + frame.headers['message']);
            console.error('Additional details: ' + frame.body);
        });
    }

    get updateObservable() {
        return this.updateTopic;
    }

    connect() {
        this.rxStomp.activate();
    }

    disconnect() {
        this.rxStomp.deactivate();
        console.log("Disconnected");
    }

    sendStatusRequest(name: string) {
        this.rxStomp.publish({
            destination: "/topic/requests",
            body: JSON.stringify({'name': name})
        });
    }
}


let service: StatusService;

export function getService():StatusService
{
    if (!service) {
        service = new StatusService();
    }
    return service;
}