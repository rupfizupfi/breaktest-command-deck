import type {DetachedModelConstructor, Value} from "@vaadin/hilla-lit-form";
import type {JSX} from "react";
import {AutoCrud, CrudService} from "@vaadin/hilla-react-crud";
import TestParameterModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameterModel";
import AutoComboBox from "Frontend/components/combobox/AutoComboBox";
import TestParameter from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameter";
import {createAutoComboBoxService} from "Frontend/components/combobox/service";
import {SampleService, TestParameterService} from "Frontend/generated/endpoints";

export function buildAutoCrud(service:CrudService<Value<TestParameterModel>>, model: DetachedModelConstructor<any>): JSX.Element  {
    const localSampleService = createAutoComboBoxService(SampleService, "name");

    return <AutoCrud
        model={model}
        service={service}
        gridProps={{
            columnOptions: {
                sample: {
                    renderer: ({item}: { item: TestParameter }) => item.sample?.name
                }
            }
        }}
        formProps={{
            hiddenFields: ['label'],
            fieldOptions: {
                type: {
                    readonly: true,
                },
                sample: {
                    renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="name" service={localSampleService}/>,
                },
            }
        }}
    />;
}