import {IFrame, IMessage, RxStomp} from "@stomp/rx-stomp";
import {Observable} from "rxjs/internal/Observable";

export default class StatusService {
    private rxStomp: RxStomp;
    private loadCellTopic: Observable<IMessage>;
    private updateLog: Observable<IMessage>;
    private frequencyConverterInfoTopic: Observable<IMessage>;

    constructor() {
        this.rxStomp = new RxStomp();
        this.rxStomp.configure({
            brokerURL: 'ws://localhost:8080/status',
        });

        this.loadCellTopic = this.rxStomp
            .watch({destination: "/topic/load-cell"});

        this.frequencyConverterInfoTopic = this.rxStomp
            .watch({destination: "/topic/frequency-converter-info"});

        this.updateLog = this.rxStomp
            .watch({destination: "/topic/logs"});

        this.rxStomp.stompErrors$.subscribe((frame: IFrame) => {
            console.error('Broker reported error: ' + frame.headers['message']);
            console.error('Additional details: ' + frame.body);
        });
    }

    get loadCellObservable() {
        return this.loadCellTopic;
    }

    get logObservable(){
        return this.updateLog;
    }

    get frequencyConverterInfoObservable(){
        return this.frequencyConverterInfoTopic;
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