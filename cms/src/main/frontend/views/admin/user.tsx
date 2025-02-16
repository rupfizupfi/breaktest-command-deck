import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import UserModel from "Frontend/generated/ch/rupfizupfi/deck/data/UserModel";
import {UserService} from "Frontend/generated/endpoints";
import {MultiSelectComboBox, PasswordField, TextField} from "@vaadin/react-components";
import AvatarField from "Frontend/components/control/AvatarField";

export const config: ViewConfig = {menu: {order: 1, icon: 'line-awesome/svg/user.svg', exclude: true}, title: 'User', loginRequired: true};

export default function UserView() {
    return (
        <AutoCrud
            service={UserService}
            model={UserModel}
            formProps={{
                visibleFields: ['username', 'name', 'newPassword', 'roles'],
                fieldOptions: {
                    username: {
                        renderer: ({field}) => <TextField {...field} label="Username"/>,
                    },
                    name: {
                        renderer: ({field}) => <TextField {...field} label="Name"/>,
                    },
                    newPassword: {
                        renderer: ({field}) => <PasswordField {...field} label="New Password"/>,
                    },
                    roles: {
                        renderer: ({field}) => <MultiSelectComboBox {...field} label="Roles" items={['ADMIN', 'USER']}/>,
                    }
                }
            }}
        />
    );
}