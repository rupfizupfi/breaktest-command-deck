import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import UserModel from "Frontend/generated/ch/rupfizupfi/deck/data/UserModel";
import {UserService} from "Frontend/generated/endpoints";

export const config: ViewConfig = {menu: {order: 1, icon: 'line-awesome/svg/user.svg'}, title: 'User', loginRequired: true};

export default function CustomerView() {
    return (
        <AutoCrud service={UserService} model={UserModel} />
    );
}
