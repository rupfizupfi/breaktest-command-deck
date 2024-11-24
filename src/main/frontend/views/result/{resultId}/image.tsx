import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {FileMetadataService, TestResultService} from "Frontend/generated/endpoints";
import {useParams} from "react-router-dom";
import React, {useEffect, useState} from "react";
import Placeholder from "Frontend/components/placeholder/Placeholder";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import {Button, VerticalLayout} from "@vaadin/react-components";
import {Upload} from "@vaadin/react-components/Upload";

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg', exclude: true}, title: 'Image Upload', loginRequired: true};

/**
 * Upload view which allows to upload images for a test result.
 * It should handle the upload of images and display the uploaded images.
 * It uses the FileMedata API to store the images.
 */
export default function ImageUploadView() {
    const resultId = useParams<{resultId: string}>().resultId;
    const [testResult, setTestResult] = useState<TestResult>();

    function handleFileRemove(file: any) {
        const images = testResult?.files || [];
        const index = images.indexOf(file);
        images.splice(index, 1);
        testResult!.files = images;
        FileMetadataService.delete(file.id);
    }

    useEffect(() => {
        TestResultService.get(parseInt(resultId || '0', 10)).then(setTestResult);
    }, [resultId]);

    if (!testResult) {
        return <Placeholder/>;
    }

    const images = testResult.files;

    /**
     * TODO: create list of the uploaded images, where one can delete them
     */
    return (
        <VerticalLayout theme="spacing-l">
            <h2>Images</h2>
            <ul>
                {images.map((file, index) => (
                    <li key={index}>
                        {file.fileName}
                        <Button onClick={() => handleFileRemove(file)}>Delete</Button>
                    </li>
                ))}
            </ul>
            <Upload
                target="/api/files/upload"
                maxFiles={5}
                onUploadResponse={(event) => {
                    const file = event.detail.file;
                    console.log(file);
                }}
            />
        </VerticalLayout>

    );
}
