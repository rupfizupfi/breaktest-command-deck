import type {DetachedModelConstructor, Value} from "@vaadin/hilla-lit-form";
import type {JSX} from "react";
import {AutoCrud, CrudService} from "@vaadin/hilla-react-crud";
import TestParameterModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameterModel";
import OwnerSelector from "cms/components/owner/OwnerSelector";
import {OwnerGridView} from "cms/components/owner/OwnerGridView";
import createEmptyValueProxy from "cms/components/owner/createEmptyValueProxy";

createEmptyValueProxy(TestParameterModel);

export function buildAutoCrud(service: CrudService<Value<TestParameterModel>>, model: DetachedModelConstructor<any>, visibleFields: string[]): JSX.Element {
    return <AutoCrud
        model={model}
        service={service}
        gridProps={{
            visibleColumns: ['owner', 'type', 'speed', 'startRampSeconds', 'stopRampSeconds', ...visibleFields],
            columnOptions: {
                owner: {
                    renderer: OwnerGridView
                }
            }
        }}
        formProps={{
            hiddenFields: ['label'],
            visibleFields: ['owner', 'type', 'speed', 'startRampSeconds', 'stopRampSeconds', ...visibleFields],
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
                    helperText: 'Lower shut-off threshold in kN (when force is falling to this value or below, the test stops automatically)',
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