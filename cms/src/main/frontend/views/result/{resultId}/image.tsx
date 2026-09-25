import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {FileMetadataService, TestResultService} from "Frontend/generated/endpoints";
import {useParams} from "react-router";
import {useEffect, useState} from "react";
import Placeholder from "cms/components/placeholder/Placeholder";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import {Button, VerticalLayout} from "@vaadin/react-components";
import {Upload} from "@vaadin/react-components/Upload";
import {Notification} from "@vaadin/react-components/Notification.js";
import FileMetadata from "Frontend/generated/ch/rupfizupfi/deck/data/FileMetadata";
import './image.css';

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg', exclude: true}, title: 'Image Upload', loginRequired: true};

/**
 * Upload view which allows to upload images for a test result.
 * It should handle the upload of images and display the uploaded images.
 * It uses the FileMedata API to store the images.
 */
export default function ImageUploadView() {
    const resultId = useParams<{ resultId: string }>().resultId;
    const [testResult, setTestResult] = useState<TestResult>();
    const [images, setImages] = useState<FileMetadata[]>([]);

    function handleFileRemove(file: FileMetadata) {
        if (file.id === undefined) {
            return;
        }
        FileMetadataService.delete(file.id)
            .then(() => setImages(current => current.filter(image => image.id !== file.id)));
    }

    useEffect(() => {
        TestResultService.get(parseInt(resultId || '0', 10)).then((result) => {
            if (result) {
                setTestResult(result);
                setImages(result.files);
            }

        });
    }, [resultId]);

    if (!testResult) {
        return <Placeholder/>;
    }

    return (
        <VerticalLayout theme="padding spacing-m">
            <h2>Images</h2>
            <ul className="image-list">
                {images.map((file, index) => (
                    <li key={index} className="image-item">
                        <img src={`/api/files/image/${file.filePath}`} alt={file.fileName} className="thumbnail"/>
                        <a href={`/api/files/image/${file.filePath}`} download>
                            <span>{file.fileName}</span>
                        </a>
                        <Button disabled={file.id === undefined} onClick={() => handleFileRemove(file)}>Delete</Button>
                    </li>
                ))}
            </ul>
            <Upload
                target="/api/files/upload"
                maxFiles={5}
                onUploadResponse={async (event) => {
                    const fileMetadata = JSON.parse(event.detail.xhr.response) as FileMetadata;
                    const testResultId = testResult.id;
                    if (testResultId === undefined) {
                        Notification.show(`Could not attach ${fileMetadata.fileName}: the test result has no id.`, {theme: 'error'});
                        return;
                    }
                    try {
                        if (!await FileMetadataService.connectToTestResult(fileMetadata, testResultId)) {
                            Notification.show(`Could not attach ${fileMetadata.fileName}: the server refused it.`, {theme: 'error'});
                            return;
                        }
                    } catch (error) {
                        Notification.show(`Could not attach ${fileMetadata.fileName}: ${error}`, {theme: 'error'});
                        return;
                    }
                    setImages(current => [...current, fileMetadata]);
                }}
            />
        </VerticalLayout>
    );
}
