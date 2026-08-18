import {ViewConfig} from '@vaadin/hilla-file-router/types.js';

export const config: ViewConfig = {
    menu: {order: 0, icon: 'line-awesome/svg/globe-solid.svg'},
    title: 'Deck',
    loginRequired: true,
};

export default function DeckView() {
    return (
        <section className="p-m gap-m">
            <h2>Welcome to the cms</h2>
        </section>
    );
}
