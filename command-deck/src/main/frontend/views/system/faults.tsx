import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {useEffect, useState} from 'react';
import {Button} from '@vaadin/react-components/Button.js';
import {Checkbox} from '@vaadin/react-components/Checkbox.js';
import {VerticalLayout} from '@vaadin/react-components';
import {SimulatedFaultService} from 'Frontend/generated/endpoints';
import type FaultState from 'Frontend/generated/ch/rupfizupfi/deck/api/services/SimulatedFaultService/FaultState';

// rolesAllowed matches the Role enum names, not the Spring authority — see views/admin/user.tsx (cms).
// Excluded from the menu: this is a bench-test tool, and it only exists at all in simulated mode.
export const config: ViewConfig = {
    menu: {exclude: true},
    title: 'Simulated faults',
    loginRequired: true,
    rolesAllowed: ['ADMIN'],
};

/**
 * Arms and clears the simulated bench faults, which is what makes the load-cell recovery path
 * exercisable without breaking a real machine: arm LOAD_CELL_STREAM_DEATH, watch the run stop and
 * hold, clear it, press Resume.
 *
 * The generated client is unconditional but the bean is not — SimulatedFaultService only exists when
 * `deck.hardware.mode=simulated`, because generation runs under `dev` where it does. On a real bench
 * every call here 404s, and that is the expected answer rather than an error to report: this view
 * says so and stops, instead of failing at whatever the first call happened to be.
 */
export default function SimulatedFaultsView() {
    const [faults, setFaults] = useState<FaultState[]>([]);
    const [unavailable, setUnavailable] = useState(false);
    const [loaded, setLoaded] = useState(false);

    // Takes the endpoint's own type rather than a tidier one: Hilla marks both the result and every
    // element as possibly absent, so the list is normalized once here instead of every render having
    // to defend against a hole in it.
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
                            checked={fault.armed}
                            onChange={(event) => apply(event.target.checked
                                ? SimulatedFaultService.arm(fault.fault)
                                : SimulatedFaultService.clear(fault.fault))}
                        />
                        <strong style={{marginLeft: 'var(--lumo-space-s)'}}>{fault.fault}</strong>
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
