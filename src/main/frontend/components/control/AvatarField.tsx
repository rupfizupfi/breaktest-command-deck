import React, {useState} from "react";
import {Upload} from "@vaadin/react-components/Upload";
import {TextFieldProps} from "@vaadin/react-components";

const AvatarField = React.forwardRef<HTMLInputElement, TextFieldProps>((props, ref) => {
    const [profilePicturePreview, setProfilePicturePreview] = useState<string | null>(null);

    const handleProfilePictureUpload = (file: File) => {
        const reader = new FileReader();
        reader.onload = () => {
            setProfilePicturePreview(URL.createObjectURL(file));
        };
        reader.readAsArrayBuffer(file);
    };

    return (<>
            <label>{props.label}</label>
            {profilePicturePreview && <img src={profilePicturePreview} alt="Profile Preview" style={{marginTop: '10px', maxWidth: '100px', maxHeight: '100px'}}/>}
            <Upload
                capture="camera"
                accept="image/*"
                maxFiles={1}
                onUploadBefore={(event) => {
                    const file = event.detail.file;
                    event.preventDefault();
                    handleProfilePictureUpload(file);
                }}
            />
            <input type="hidden" ref={ref} value={profilePicturePreview || ''}/>
        </>
    );
});

export default AvatarField;
