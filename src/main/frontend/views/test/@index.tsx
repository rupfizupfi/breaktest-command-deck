import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {VerticalLayout} from "@vaadin/react-components";
import {useNavigate} from "react-router-dom";

export const config: ViewConfig = {
    menu: {order: 0, icon: 'line-awesome/svg/suitcase-rolling-solid.svg'},
    title: 'Test',
    loginRequired: true,
};

export default function TestView() {
    const navigate = useNavigate();

    return (
        <VerticalLayout theme="padding spacing-l stretch evenly">
            <h1>Choose test type</h1>
            <nav>
                <button onClick={() => navigate('destructive')}>Destructive</button>
                <button onClick={() => navigate('non-destructive')}>Non Destructive</button>
            </nav>
        </VerticalLayout>
    )
}