import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {Button, VerticalLayout} from "@vaadin/react-components";
import {useNavigate} from "react-router-dom";

export const config: ViewConfig = {
    menu: {order: 4, icon: 'line-awesome/svg/suitcase-rolling-solid.svg'},
    title: 'Test',
    loginRequired: true,
};

export default function TestView() {
    const navigate = useNavigate();

    return (
        <VerticalLayout theme="padding spacing-l stretch evenly">
            <h1>Choose test type</h1>
            <nav>
                <Button theme="secondary" onClick={() => navigate('destructive')}>Destructive</Button>
                <Button theme="secondary" onClick={() => navigate('cyclic')}>Cyclic</Button>
                <Button theme="secondary" onClick={() => navigate('timeCyclic')}>Time Cyclic</Button>
            </nav>
        </VerticalLayout>
    )
}