import type {DetachedModelConstructor, Value} from "@vaadin/hilla-lit-form";
import type {JSX} from "react";
import {AutoCrud, CrudService} from "@vaadin/hilla-react-crud";
import TestParameterModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameterModel";
import AutoComboBox from "Frontend/components/combobox/AutoComboBox";
import TestParameter from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameter";
import {createAutoComboBoxService} from "Frontend/components/combobox/service";
import {SampleService, TestParameterService} from "Frontend/generated/endpoints";

export function buildAutoCrud(service:CrudService<Value<TestParameterModel>>, model: DetachedModelConstructor<any>, visibleFiels: string[]): JSX.Element  {
    const localSampleService = createAutoComboBoxService(SampleService, "name");

    return <AutoCrud
        model={model}
        service={service}
        gridProps={{
            visibleColumns: ['type', 'sample', 'speed', 'startRampSeconds', 'stopRampSeconds', ...visibleFiels],
            columnOptions: {
                sample: {
                    renderer: ({item}: { item: TestParameter }) => item.sample?.name
                }
            }
        }}
        formProps={{
            hiddenFields: ['label'],
            visibleFields: ['type', 'sample', 'speed', 'startRampSeconds','stopRampSeconds', ...visibleFiels],
            fieldOptions: {
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
                },

                sample: {
                    renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="name" service={localSampleService}/>,
                },
            }
        }}
    />;
}