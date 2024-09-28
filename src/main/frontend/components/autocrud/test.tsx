import type {DetachedModelConstructor, Value} from "@vaadin/hilla-lit-form";
import type {JSX} from "react";
import {AutoCrud, CrudService} from "@vaadin/hilla-react-crud";
import TestParameterModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameterModel";
import OwnerSelector from "Frontend/components/owner/OnwerSelector";
import {OwnerGridView} from "Frontend/components/owner/OwnerGridView";

export function buildAutoCrud(service: CrudService<Value<TestParameterModel>>, model: DetachedModelConstructor<any>, visibleFiels: string[]): JSX.Element {
    return <AutoCrud
        model={model}
        service={service}
        gridProps={{
            visibleColumns: ['type', 'speed', 'startRampSeconds', 'stopRampSeconds', ...visibleFiels],
            columnOptions: {
                owner: {
                    renderer: OwnerGridView
                }
            }
        }}
        formProps={{
            hiddenFields: ['label'],
            visibleFields: ['type', 'speed', 'startRampSeconds', 'stopRampSeconds', ...visibleFiels],
            fieldOptions: {
                owner: {
                    renderer: ({field}) => <OwnerSelector {...field} />,
                },
                type: {
                    readonly: true,
                },
                speed: {
                    helperText: 'Speed in mm/min',
                },

                upperShutOffThreshold: {
                    helperText: 'Upper shut-off threshold in kN (when force is reaching this value and more, the test stops automatically)',
                },

                lowerShutOffThreshold: {
                    helperText: 'Lower shut-off threshold in kN (stops fu))',
                },

                upperTurnForce: {
                    helperText: 'Upper turn force in kN',
                },

                lowerTurnForce: {
                    helperText: 'Lower turn force in kN',
                },

                startRampSeconds: {
                    helperText: 'Start ramp time in seconds',
                },

                stopRampSeconds: {
                    helperText: 'Stop ramp time in seconds',
                }
            }
        }}
    />;
}