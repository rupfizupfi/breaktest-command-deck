import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {useEffect, useState} from 'react';
import {Button} from '@vaadin/react-components/Button.js';
import {Checkbox} from '@vaadin/react-components/Checkbox.js';
import {VerticalLayout} from '@vaadin/react-components';
import {SimulatedFaultService} from 'Frontend/generated/endpoints';
import type FaultState from 'Frontend/generated/ch/rupfizupfi/deck/api/services/SimulatedFaultService/FaultState';

// rolesAllowed matches the Role enum names, not the Spring authority — see views/admin/user.tsx (cms).
// Excluded from the menu: a bench-test tool that only works in simulated mode.
export const config: ViewConfig = {
    menu: {exclude: true},
    title: 'Simulated faults',
    loginRequired: true,
    rolesAllowed: ['ADMIN'],
};

/**
 * Arms and clears simulated bench faults to exercise the load-cell recovery path: arm
 * LOAD_CELL_STREAM_DEATH, watch the run stop and hold, clear it, press Resume.
 *
 * SimulatedFaultService only exists under `deck.hardware.mode=simulated`, though the client is always
 * generated. On a real bench every call 404s, and the view reports it as unavailable.
 */
export default function SimulatedFaultsView() {
    const [faults, setFaults] = useState<FaultState[]>([]);
    const [unavailable, setUnavailable] = useState(false);
    const [loaded, setLoaded] = useState(false);

    // Hilla marks the result and every element as possibly absent; normalize once here.
    function apply(call: Promise<ReadonlyArray<FaultState | undefined> | undefined>) {
        call
            .then((next) => {
                setFaults((next ?? []).filter((fault): fault is FaultState => fault !== undefined));
                setUnavailable(false);
            })
            .catch(() => setUnavailable(true))
            .finally(() => setLoaded(true));
    }

    useEffect(() => {
        apply(SimulatedFaultService.list());
    }, []);

    if (unavailable) {
        return (
            <VerticalLayout theme="padding spacing-l">
                <h1>Simulated faults</h1>
                <p>
                    Not available. The fault switches exist only when the deck runs with
                    <code> deck.hardware.mode=simulated</code>; on a real bench this endpoint is not
                    merely forbidden, it does not exist. Nothing was armed or cleared.
                </p>
            </VerticalLayout>
        );
    }

    return (
        <VerticalLayout theme="padding spacing-l stretch">
            <h1>Simulated faults</h1>
            <p>
                Arming a fault makes the simulated hardware misbehave on the next run. This is the only
                way to exercise the loss-and-resume path end to end; a fault left armed will break the
                next run somebody starts.
            </p>

            {loaded && faults.length === 0 && <p>No fault switches are defined.</p>}

            <ul style={{listStyle: 'none', padding: 0, width: '100%'}}>
                {faults.map((fault) => (
                    <li key={fault.fault} style={{padding: 'var(--lumo-space-xs) 0'}}>
                        <Checkbox
                            label={fault.fault}
                            checked={fault.armed}
                            onChange={(event) => apply(event.target.checked
                                ? SimulatedFaultService.arm(fault.fault)
                                : SimulatedFaultService.clear(fault.fault))}
                            style={{fontWeight: 600}}
                        />
                        <div style={{marginLeft: '2.2em', color: 'var(--lumo-secondary-text-color)'}}>
                            {fault.description}
                        </div>
                    </li>
                ))}
            </ul>

            <Button theme="error" onClick={() => apply(SimulatedFaultService.clearAll())}>
                Clear all
            </Button>
        </VerticalLayout>
    );
}
