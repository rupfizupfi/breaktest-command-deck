import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import {CustomerService, ProjectService} from "Frontend/generated/endpoints";
import ProjectModel from "Frontend/generated/ch/rupfizupfi/deck/data/ProjectModel";
import AutoComboBox from "Frontend/components/combobox/AutoComboBox";
import Project from "Frontend/generated/ch/rupfizupfi/deck/data/Project";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";
import {TextField} from "@vaadin/react-components";
import {createAutoComboBoxService} from "Frontend/components/combobox/service";

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg'}, title: 'Projects', loginRequired: true};

export default function ProjectView() {
    const service = createAutoComboBoxService(CustomerService, "firstname");

    return (
        <AutoCrud
            model={ProjectModel}
            service={ProjectService}
            gridProps={{
                columnOptions: {
                    customer: {
                        renderer: ({item}: { item: Project }) => <span>{item.customer?.label}</span>,
                        headerFilterRenderer: ({setFilter}) => (
                            <TextField
                                placeholder='Filter...'
                                onValueChanged={({detail}) =>
                                    setFilter({
                                        "@type": "or",
                                        "children": [
                                            {
                                                propertyId: 'customer.organization',
                                                filterValue: detail.value,
                                                matcher: Matcher.CONTAINS,
                                                '@type': 'propertyString',
                                            },
                                            {
                                                propertyId: 'customer.firstname',
                                                filterValue: detail.value,
                                                matcher: Matcher.CONTAINS,
                                                '@type': 'propertyString',
                                            },
                                            {
                                                propertyId: 'customer.lastname',
                                                filterValue: detail.value,
                                                matcher: Matcher.CONTAINS,
                                                '@type': 'propertyString',
                                            }]
                                    })
                                }
                            />
                        )
                    }
                }
            }}
            formProps={{
                fieldOptions: {
                    customer: {
                        renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="label" itemLabelPath="label" service={service}/>,
                    }
                }
            }}
        />
    );
}
