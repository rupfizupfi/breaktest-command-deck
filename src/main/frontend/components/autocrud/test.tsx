import type {DetachedModelConstructor, Value} from "@vaadin/hilla-lit-form";
import type {JSX} from "react";
import {AutoCrud, CrudService} from "@vaadin/hilla-react-crud";
import TestParameterModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameterModel";
import TestParameter from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameter";
import OwnerSelector from "Frontend/components/owner/OnwerSelector";

export function buildAutoCrud(service: CrudService<Value<TestParameterModel>>, model: DetachedModelConstructor<any>, visibleFiels: string[]): JSX.Element {
    return <AutoCrud
        model={model}
        service={service}
        gridProps={{
            visibleColumns: ['type', 'speed', 'startRampSeconds', 'stopRampSeconds', ...visibleFiels],
            columnOptions: {
                owner: {
                    renderer: ({item}: { item: TestParameter }) => item.owner?.username + ' (' + item.owner?.name + ')'
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