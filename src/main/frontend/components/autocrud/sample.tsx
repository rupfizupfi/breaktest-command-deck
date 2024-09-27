import {AutoCrud, CrudService} from "@vaadin/hilla-react-crud";
import SampleModel from "Frontend/generated/ch/rupfizupfi/deck/data/SampleModel";
import AutoComboBox from "Frontend/components/combobox/AutoComboBox";
import AutMultiSelectComboBox from "Frontend/components/combobox/MultiSelectComboBox";
import {createAutoComboBoxService} from "Frontend/components/combobox/service";
import type {Value} from "@vaadin/hilla-lit-form";
import type {JSX} from "react";
import {GearStandardService, GearTypeService, MaterialService, ProjectService} from "Frontend/generated/endpoints";
import Sample from "Frontend/generated/ch/rupfizupfi/deck/data/Sample";


export function buildAutoCrud(service:CrudService<Value<SampleModel>>): JSX.Element  {
    const projectService = createAutoComboBoxService(ProjectService, "name");
    const gearTypeService = createAutoComboBoxService(GearTypeService, "name");
    const materialService = createAutoComboBoxService(MaterialService, "name");
    const gearStandardService = createAutoComboBoxService(GearStandardService, "name");

    return <AutoCrud
        service={service}
        model={SampleModel}
        gridProps={{
            columnOptions: {
                owner: {
                    renderer: ({item}: { item: TestResult }) => item.owner?.username + ' (' + item.owner?.name + ')'
                },
                project: {
                    renderer: ({item}: { item: Sample }) => <a href={`/project/${item.project?.id}/sample`}>{item.project?.name}</a>
                }
            }
        }}
        formProps={{
            visibleFields: ['name', 'description', 'project', 'gearTypes', 'gearStandards', 'materials', 'manufacturer', 'model', 'yearOfManufacture'],
            fieldOptions: {
                project: {
                    renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="name" service={projectService}/>,
                },
                gearTypes: {
                    renderer: ({field}) => <AutMultiSelectComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="name" service={gearTypeService}/>,
                },
                gearStandards: {
                    renderer: ({field}) => <AutMultiSelectComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="name" service={gearStandardService}/>,
                },
                materials: {
                    renderer: ({field}) => <AutMultiSelectComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="name" service={materialService}/>,
                }
            }
        }}/>
}