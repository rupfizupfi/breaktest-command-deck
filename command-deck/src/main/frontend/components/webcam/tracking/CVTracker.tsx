import cvReady, {Tracker} from "@techstark/opencv-js";
import {useRef} from "react";
import useAreaSelector, {SelectedArea} from "Frontend/components/webcam/tracking/AreaSelector";

export interface TrackedObject {
    roi: SelectedArea;
    tracker: Tracker;
}

export default function useCVTracker() {
    const trackedObjects = useRef<TrackedObject[]>([]);

    function init(canvas: HTMLCanvasElement) {
        const areaSelector = useAreaSelector(canvas, (selection) => {
            const ctx = canvas.getContext("2d")!;
            const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
            let trackedObject = {
                roi: selection,
                tracker: new cv.TrackerCSRT_create() // Du kannst auch andere Tracker verwenden
            };
            trackedObject.tracker.init(imageData, selection);
            trackedObjects.current.push(trackedObject);
        });

        return () => {
            areaSelector.removeEventListeners();
        };
    }

    function processFrames(canvas: HTMLCanvasElement, scaleFactor: number) {
        const ctx = canvas.getContext("2d")!;
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);

        for (let trackedObject of trackedObjects.current) {
            let tracked = trackedObject.tracker.update(imageData, trackedObject.roi);
            if (tracked) {
                ctx.strokeStyle = 'green';
                ctx.lineWidth = 2;
                ctx.strokeRect(trackedObject.roi.x, trackedObject.roi.y, trackedObject.roi.width, trackedObject.roi.height);
            }
        }
    }

    return {
        init,
        processFrames
    };
}