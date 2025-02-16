import React, {useCallback, useRef, useState} from "react";
import {Upload} from "@vaadin/react-components/Upload";
import {TextFieldProps} from "@vaadin/react-components";

const AvatarField = React.forwardRef<HTMLInputElement, TextFieldProps>((props, ref) => {
    const [profilePicturePreview, setProfilePicturePreview] = useState<string>('');
    const inputRef = useRef<HTMLInputElement | null>(null);

    const setInputRef = useCallback((element: HTMLInputElement | null) => {
        if (element) {
            inputRef.current = element;

            if (ref) {
                // @ts-ignore
                ref(element);
            }

            const originalValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');

            Object.defineProperty(element, 'value', {
                set(value) {
                    console.log('set', value);

                    if (value && Array.isArray(value)) {
                        if (value.length === 0) {
                            value = '';
                        } else {
                            const byteArray = new Uint8Array(value);
                            const binary = byteArray.reduce((data, byte) => data + String.fromCharCode(byte), '');
                            value = `data:image/jpeg;base64,${btoa(binary)}`;
                        }
                    }

                    if (value instanceof ArrayBuffer) {
                        const byteArray = new Uint8Array(value);
                        const binary = byteArray.reduce((data, byte) => data + String.fromCharCode(byte), '');
                        value = `data:image/jpeg;base64,${btoa(binary)}`;
                    }

                    if (originalValueSetter) {
                        originalValueSetter.set?.call(element, value);
                    }

                    requestAnimationFrame(() => {
                        setProfilePicturePreview(value);
                    });
                },
                get() {
                    let value = originalValueSetter?.get?.call(element);

                    if (value  && value.startsWith('data:image/jpeg;base64,')) {
                        // to byte array
                        const binary = atob(value.split(',')[1]);
                        const byteArray = new Uint8Array(binary.length);
                        for (let i = 0; i < binary.length; i++) {
                            byteArray[i] = binary.charCodeAt(i);
                        }
                        value = byteArray;
                    }

                    console.log('get', value);

                    return value;
                }
            });
        }
    }, [inputRef.current]);


    const handleProfilePictureUpload = (file: File) => {
        const reader = new FileReader();

        reader.onload = () => {
            // @ts-ignore
            inputRef.current!.value = reader.result;
            // @ts-ignore
            inputRef.current!.dispatchEvent(new Event('input', { bubbles: true }));
        };
        reader.readAsArrayBuffer(file);
    };

    return (
        <div>
            <label>{props.label}</label>
            {profilePicturePreview && <img src={profilePicturePreview} alt="Profile Preview" style={{marginTop: '10px', maxWidth: '100px', maxHeight: '100px'}}/>}
            <Upload
                capture="camera"
                accept="image/jpeg"
                maxFiles={1}
                onUploadBefore={(event) => {
                    const file = event.detail.file;
                    event.preventDefault();
                    handleProfilePictureUpload(file);
                }}
            />
            <input type="hidden" ref={setInputRef}/>
        </div>
    );
});

export default AvatarField;