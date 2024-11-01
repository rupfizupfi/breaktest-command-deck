import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import {CustomerService} from "Frontend/generated/endpoints";
import CustomerModel from "Frontend/generated/ch/rupfizupfi/deck/data/CustomerModel";

export const config: ViewConfig = {menu: {order: 1, icon: 'line-awesome/svg/file.svg'}, title: 'Customer', loginRequired: true};

export default function CustomerView() {
    return (
        <AutoCrud service={CustomerService} model={CustomerModel} formProps={{hiddenFields: ['label']}}/>
    );
}
