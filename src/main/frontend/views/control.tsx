import {ViewConfig} from "@vaadin/hilla-file-router/types.js";
import {useEffect, useState} from "react";
import {Button, VerticalLayout} from "@vaadin/react-components";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import {Icon} from "@vaadin/react-components/Icon.js";
import './control.css';

export const config: ViewConfig = {menu: {order: 10, icon: 'line-awesome/svg/cogs-solid.svg'}, title: 'Control board', loginRequired: true};

export default function ControlBoard() {
    const [force, setForce] = useState<number>(0);
    const service = getService();

    useEffect(() => {
        const subscription = service.loadCellObservable.subscribe((value: IMessage) => {
            const newStatus: object[] = JSON.parse(value.body);
            setForce(newStatus.reduce((prev, item: any) => Math.max(prev, item.force), 0));
        });

        service.connectComponent(ControlBoard);
        return () => {
            subscription.unsubscribe();
            service.disconnectComponent(ControlBoard);
        }
    }, [service]);

    const handleControl = (command: string) => {
        // Implement control logic here
        console.log(`Command: ${command}`);
    };

    return (
        <VerticalLayout className="control-board" theme="spacing-l padding">
            <h1 className="control-board__title">{force.toFixed(2)} kN</h1>
            <div className="control-board__button-group">
                <Button className="control-board__button" theme="primary large" onClick={() => handleControl('slow-reverse')}>
                    <Icon icon="vaadin:arrow-left" slot="prefix" />
                    Slow Reverse
                </Button>
                <Button className="control-board__button" theme="primary large" onClick={() => handleControl('fast-reverse')}>
                    <Icon icon="vaadin:fast-backward" slot="prefix" />
                    Fast Reverse
                </Button>
            </div>
            <div className="control-board__button-group">
                <Button className="control-board__button" theme="primary large" onClick={() => handleControl('slow-forward')}>
                    <Icon icon="vaadin:arrow-right" slot="prefix" />
                    Slow Forward
                </Button>
                <Button className="control-board__button" theme="primary large" onClick={() => handleControl('fast-forward')}>
                    <Icon icon="vaadin:fast-forward" slot="prefix" />
                    Fast Forward
                </Button>
            </div>
        </VerticalLayout>
    );
}