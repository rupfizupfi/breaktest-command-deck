import cv from "@techstark/opencv-js";
import {useRef} from "react";
import useAreaSelector, {SelectedArea} from "Frontend/components/webcam/tracking/AreaSelector";

export default function useCamShiftTracking() {
    const trackingWindowRef = useRef<cv.Rect | null>(null);
    const trackingPointRef = useRef<{ x: number, y: number } | null>(null);
    const histRef = useRef<cv.Mat | null>(null);

    function init(canvas: HTMLCanvasElement) {
        const areaSelector = useAreaSelector(canvas, (selection) => {
            startTracking(selection, canvas.getContext("2d")!, canvas);
        });

        return () => {
            areaSelector.removeEventListeners();
            if (histRef.current) {
                histRef.current.delete();
            }
        };
    }

    function startTracking(selection: SelectedArea, ctx: CanvasRenderingContext2D, canvas: HTMLCanvasElement) {
        trackingWindowRef.current = new cv.Rect(selection.x, selection.y, selection.width, selection.height);

        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const frame = cv.matFromImageData(imageData);

        // Convert frame from RGBA to HSV directly
        const hsv = new cv.Mat();
        cv.cvtColor(frame, hsv, cv.COLOR_RGB2HSV);

        // Define the region of interest (ROI)
        const rawRoi = hsv.roi(trackingWindowRef.current);
        const roi = new cv.Mat();
        cv.cvtColor(rawRoi, roi, cv.COLOR_RGBA2RGB);
        cv.cvtColor(roi, roi, cv.COLOR_RGB2HSV);

        // Create mask
        const mask = new cv.Mat();
        const lowScalar = new cv.Scalar(30, 30, 0);
        const highScalar = new cv.Scalar(180, 180, 180);
        const low = new cv.Mat(roi.rows, roi.cols, roi.type(), lowScalar);
        const high = new cv.Mat(roi.rows, roi.cols, roi.type(), highScalar);
        cv.inRange(roi, low, high, mask);

        // Calculate histogram
        const hist = new cv.Mat();
        const channels = [0]; // Hue channel
        const histSize = [180];
        const ranges = [0, 180];
        const matVector = new cv.MatVector();
        matVector.push_back(roi);
        cv.calcHist(matVector, channels, mask, hist, histSize, ranges);
        cv.normalize(hist, hist, 0, 255, cv.NORM_MINMAX);

        // Store histogram reference
        if (histRef.current) {
            histRef.current.delete();
        }
        histRef.current = hist;

        // Clean up
        frame.delete();
        hsv.delete();
        roi.delete();
        mask.delete();
        rawRoi.delete();
        low.delete();
        high.delete();
    }

    function processFrames(canvas: HTMLCanvasElement, scaleFactor: number) {
        const ctx = canvas.getContext("2d")!;
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const frame = cv.matFromImageData(imageData);
        let realMovement = 0;

        if (histRef.current && trackingWindowRef.current) {
            // Convert frame from RGBA to HSV directly
            const hsv = new cv.Mat();
            cv.cvtColor(frame, hsv, cv.COLOR_RGB2HSV);

            // Calculate back projection
            const backProj = new cv.Mat();
            const channels = [0]; // Hue channel
            const ranges = [0, 180];
            const matVector = new cv.MatVector();
            matVector.push_back(hsv);
            cv.calcBackProject(matVector, channels, histRef.current, backProj, ranges, 1);

            // Apply CAMShift to get the new location
            const termCriteria = new cv.TermCriteria(cv.TermCriteria_EPS | cv.TermCriteria_COUNT, 10, 1);
            // @ts-ignore
            const [trackBox, trackWindow] = cv.CamShift(backProj, trackingWindowRef.current, termCriteria);
            // @ts-ignore
            let pts = cv.rotatedRectPoints(trackBox);
            cv.line(frame, pts[0], pts[1], [255, 0, 0, 255], 3);
            cv.line(frame, pts[1], pts[2], [255, 0, 0, 255], 3);
            cv.line(frame, pts[2], pts[3], [255, 0, 0, 255], 3);
            cv.line(frame, pts[3], pts[0], [255, 0, 0, 255], 3);

            // Calculate movement
            const currentCenter = trackWindow;
            if (trackingPointRef.current) {
                const dx = currentCenter.x - trackingPointRef.current.x;
                const dy = currentCenter.y - trackingPointRef.current.y;
                const pixelMovement = Math.sqrt(dx * dx + dy * dy);
                realMovement = pixelMovement / scaleFactor; // Convert to cm
            }
            trackingPointRef.current = {x: currentCenter.x, y: currentCenter.y};

            // Update tracking window
            trackingWindowRef.current = trackWindow;

            // Clean up
            hsv.delete();
            backProj.delete();
        }

        // Display the result
        cv.imshow(canvas, frame);

        if (realMovement > 0) {
            // Display movement
            ctx.font = "20px Arial";
            ctx.fillStyle = "blue";
            ctx.fillText(`Movement: ${realMovement.toFixed(2)} cm`, 10, 30);
        }

        // Clean up
        frame.delete();
    }

    return {
        processFrames,
        init
    };
}