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
import {Load, parseRouteId, settleLoad} from "cms/util/load";
import {csrfHeaders} from "cms/util/csrf";
import './image.css';

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg', exclude: true}, title: 'Image Upload', loginRequired: true};

/** Uploads and lists a test result's images, stored through FileMetadata. */
export default function ImageUploadView() {
    const resultId = useParams<{ resultId: string }>().resultId;
    const [load, setLoad] = useState<Load<TestResult>>({kind: 'loading'});
    const [images, setImages] = useState<FileMetadata[]>([]);
    // Ids with a delete in flight: their button is disabled so a double click cannot send two.
    const [deleting, setDeleting] = useState<ReadonlySet<number>>(new Set());

    function handleFileRemove(file: FileMetadata) {
        const id = file.id;
        if (id === undefined) {
            return;
        }
        setDeleting(current => new Set(current).add(id));
        FileMetadataService.delete(id)
            .then(() => setImages(current => current.filter(image => image.id !== id)))
            // The rpc middleware toasts the cause; the image stays listed because it still exists.
            .catch(() => undefined)
            .finally(() => setDeleting(current => {
                const next = new Set(current);
                next.delete(id);
                return next;
            }));
    }

    useEffect(() => {
        const id = parseRouteId(resultId);
        if (id === undefined) {
            setLoad({kind: 'not-found'});
            return;
        }

        // A slow answer for the previous id must not replace the current one.
        let cancelled = false;
        setLoad({kind: 'loading'});
        settleLoad(TestResultService.get(id)).then((next) => {
            if (cancelled) {
                return;
            }
            setLoad(next);
            setImages(next.kind === 'found' ? next.value.files : []);
        });
        return () => {
            cancelled = true;
        };
    }, [resultId]);

    if (load.kind === 'loading') {
        return <Placeholder/>;
    }
    if (load.kind !== 'found') {
        return (
            <div className="flex flex-col h-full items-center justify-center p-l text-center box-border">
                {/* Decorative: the heading below says what happened. */}
                <img style={{width: '200px'}} src="images/empty-plant.png" alt=""/>
                <h2>{load.kind === 'not-found'
                    ? `Result with id "${resultId}" doesn't exist`
                    : `Result "${resultId}" could not be loaded`}</h2>
            </div>
        );
    }
    const testResult = load.value;

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
                        <Button disabled={file.id === undefined || deleting.has(file.id)} onClick={() => handleFileRemove(file)}>Delete</Button>
                    </li>
                ))}
            </ul>
            <Upload
                target="/api/files/upload"
                // Vaadin 25 defaults to a raw body; FileEndpoint reads the multipart part "file".
                uploadFormat="multipart"
                // A plain XHR outside the Hilla client: without the token Spring refuses it with 403.
                headers={csrfHeaders()}
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
