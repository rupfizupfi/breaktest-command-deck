import {type ReactNode, useEffect, useState} from "react";
import {Button} from "@vaadin/react-components/Button.js";
import Placeholder from "cms/components/placeholder/Placeholder";
import {currentUser} from "cms/components/owner/createEmptyValueProxy";

type GateState = 'loading' | 'ready' | 'error';

/**
 * Renders its children only once the signed-in user is known. Wrap every AutoCrud whose model uses
 * {@code createEmptyValueProxy}: its form takes the empty value when it mounts, so mounting it
 * earlier would make each new row silently shared (owner: all users). A failed lookup shows a
 * retry instead of the form; the rpc middleware has already toasted the cause.
 */
export default function CurrentUserGate({children}: {children: ReactNode}) {
    const [state, setState] = useState<GateState>(() => currentUser.peek().known ? 'ready' : 'loading');
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
        if (currentUser.peek().known) {
            setState('ready');
            return;
        }
        let cancelled = false;
        currentUser.get().then(
            () => !cancelled && setState('ready'),
            () => !cancelled && setState('error'));
        return () => {
            cancelled = true;
        };
    }, [attempt]);

    switch (state) {
        case 'ready':
            return <>{children}</>;
        case 'loading':
            return <Placeholder/>;
        case 'error':
            return (
                <div className="flex flex-col items-center p-l gap-m text-center">
                    <p>The signed-in user could not be determined, so a new record would not get you as its owner.</p>
                    <Button theme="primary" onClick={() => {
                        setState('loading');
                        setAttempt(a => a + 1);
                    }}>Retry</Button>
                </div>
            );
    }
}
