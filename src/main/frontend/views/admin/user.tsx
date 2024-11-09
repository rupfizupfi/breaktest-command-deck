import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import UserModel from "Frontend/generated/ch/rupfizupfi/deck/data/UserModel";
import {UserService} from "Frontend/generated/endpoints";
import {TextField, PasswordField, MultiSelectComboBox, Upload} from "@vaadin/react-components";
import {useState, useRef} from "react";

export const config: ViewConfig = {menu: {order: 1, icon: 'line-awesome/svg/user.svg', exclude: true}, title: 'User', loginRequired: true};

export default function UserView() {
    const [profilePicture, setProfilePicture] = useState<Uint8Array | null>(null);
    const [profilePicturePreview, setProfilePicturePreview] = useState<string | null>(null);
    const uploadRef = useRef(null);

    const handleProfilePictureUpload = (file: File) => {
        const reader = new FileReader();
        reader.onload = () => {
            const arrayBuffer = reader.result as ArrayBuffer;
            setProfilePicture(new Uint8Array(arrayBuffer));
            setProfilePicturePreview(URL.createObjectURL(file));
        };
        reader.readAsArrayBuffer(file);
    };

    return (
        <AutoCrud
            service={UserService}
            model={UserModel}
            formProps={{
                visibleFields: ['username', 'name', 'newPassword', 'roles', 'profilePicture'],
                fieldOptions: {
                    username: {
                        renderer: ({field}) => <TextField {...field} label="Username" />,
                    },
                    name: {
                        renderer: ({field}) => <TextField {...field} label="Name" />,
                    },
                    newPassword: {
                        renderer: ({field}) => <PasswordField {...field} label="New Password" />,
                    },
                    roles: {
                        renderer: ({field}) => <MultiSelectComboBox {...field} label="Roles" items={['ADMIN', 'USER']} />,
                    },
                    profilePicture: {
                        renderer: ({field}) => (
                            <>
                                <Upload
                                    ref={uploadRef}
                                    accept="image/*"
                                    maxFiles={1}
                                    autoSave="false"
                                    onUploadBefore={(event) => {
                                        event.preventDefault();
                                        const file = event.detail.file;
                                        handleProfilePictureUpload(file);
                                    }}
                                />
                                {profilePicturePreview && <img src={profilePicturePreview} alt="Profile Preview" style={{ marginTop: '10px', maxWidth: '100px', maxHeight: '100px' }} />}
                                <input
                                    type="hidden"
                                    {...field}
                                    value={profilePicture ? JSON.stringify(Array.from(profilePicture)) : ''}
                                />
                            </>
                        ),
                    },
                }
            }}
        />
    );
}