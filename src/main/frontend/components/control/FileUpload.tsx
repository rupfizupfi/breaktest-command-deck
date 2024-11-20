import React, {useState} from 'react';
import {Upload, UploadFile,  UploadProps} from '@vaadin/react-components/Upload';
import {Button, UploadElement} from '@vaadin/react-components';

const FileUpload = React.forwardRef<UploadElement, UploadProps>((props, ref) => {
    const [files, setFiles] = useState<UploadFile[]>([]);

    return (<>
            <Upload
                target="/api/files/upload"
                files={files}
                {...props}
                ref={ref}
                maxFiles={5}
            />
            <Button onClick={() => setFiles([])}>Clear Files</Button>
        </>
    );
});

export default FileUpload;