import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {HorizontalLayout, VerticalLayout} from "@vaadin/react-components";
import DistanceMeasureCam from "Frontend/components/webcam/DistanceMeasureCam";

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg', exclude: true}, title: 'Cam object tracking', loginRequired: true};

/**
 * It is possible that there are multiple devices using this page which track the movement of the objects.
 */
export default function TrackingView() {
    return (
        <VerticalLayout theme="spacing-l stretch evenly h-full min-h-full">
            <HorizontalLayout theme="spacing" style={{alignItems: 'center', justifyContent: 'space-between', padding: '1em'}}>
                <h2>Cam Object Tracking</h2>
            </HorizontalLayout>
            <p>
                This view displays the camera with tracking capabilities. During a test run, the camera tracks the movement of objects. You can select the object to track by touching or clicking on it.
            </p>
            <DistanceMeasureCam />
        </VerticalLayout>
    );
}
