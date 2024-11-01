import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import {CustomerService, ProjectService} from "Frontend/generated/endpoints";
import ProjectModel from "Frontend/generated/ch/rupfizupfi/deck/data/ProjectModel";
import AutoComboBox from "Frontend/components/combobox/AutoComboBox";
import Project from "Frontend/generated/ch/rupfizupfi/deck/data/Project";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";
import {GridColumn, TextField} from "@vaadin/react-components";
import {createAutoComboBoxService} from "Frontend/components/combobox/service";
import OwnerSelector from "Frontend/components/owner/OnwerSelector";
import {OwnerGridView} from "Frontend/components/owner/OwnerGridView";
import createEmptyValueProxy from "Frontend/components/owner/createEmptyValueProxy";
import {Link} from "react-router-dom";
import React from "react";

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg'}, title: 'Projects', loginRequired: true};

createEmptyValueProxy(ProjectModel);

export default function ProjectView() {
    const service = createAutoComboBoxService(CustomerService, "firstname");

    return (
        <AutoCrud
            model={ProjectModel}
            service={ProjectService}
            gridProps={{
                columnOptions: {
                    owner: {
                        renderer: OwnerGridView
                    },
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
                },
                customColumns: [
                    <GridColumn key="results" renderer={({item}: { item: Project }) => <Link target="_blank" to={`/api/DownloadEndpoint/project/${item.id}`}>Export</Link>} header="Results" autoWidth/>
                ]
            }}
            formProps={{
                fieldOptions: {
                    owner: {
                        renderer: ({field}) => <OwnerSelector {...field} />,
                    },
                    customer: {
                        renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="label" itemLabelPath="label" service={service}/>,
                    }
                }
            }}
        />
    );
}
