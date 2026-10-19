import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import {CustomerService} from "Frontend/generated/endpoints";
import CustomerModel from "Frontend/generated/ch/rupfizupfi/deck/data/CustomerModel";
import OwnerSelector from "cms/components/owner/OwnerSelector";
import {OwnerGridView} from "cms/components/owner/OwnerGridView";
import createEmptyValueProxy from "cms/components/owner/createEmptyValueProxy";
import CurrentUserGate from "cms/components/owner/CurrentUserGate";

export const config: ViewConfig = {menu: {order: 1, icon: 'line-awesome/svg/file.svg'}, title: 'Customer', loginRequired: true};

createEmptyValueProxy(CustomerModel);

export default function CustomerView() {
    return (
        <CurrentUserGate>
            <AutoCrud
                service={CustomerService}
                model={CustomerModel}
                gridProps={{
                    columnOptions: {
                        owner: {
                            renderer: OwnerGridView
                        }
                    }
                }}
                formProps={{
                    hiddenFields: ['label'],
                    fieldOptions: {
                        owner: {
                            renderer: ({field}) => <OwnerSelector {...field} />,
                        }
                    }
                }}
            />
        </CurrentUserGate>
    );
}
