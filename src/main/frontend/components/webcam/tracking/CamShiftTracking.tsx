import cv from "@techstark/opencv-js";
import {useRef} from "react";

export default function useCamShiftTracking() {
    const trackingWindowRef = useRef<cv.Rect | null>(null);
    const trackingPointRef = useRef<{ x: number, y: number } | null>(null);
    const histRef = useRef<cv.Mat | null>(null);

    function startTracking(x: number, y: number, ctx: CanvasRenderingContext2D, canvas: HTMLCanvasElement) {
        const windowSize = 10; // Adjust as needed
        const startX = Math.max(0, x - windowSize / 2);
        const startY = Math.max(0, y - windowSize / 2);
        const width = Math.min(windowSize, canvas.width - startX);
        const height = Math.min(windowSize, canvas.height - startY);
        trackingWindowRef.current = new cv.Rect(startX, startY, width, height);

        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const frame = cv.matFromImageData(imageData);

        // Convert frame from RGBA to HSV directly
        const hsv = new cv.Mat();
        cv.cvtColor(frame, hsv, cv.COLOR_RGB2HSV);

        // Define the region of interest (ROI)
        const roi = hsv.roi(trackingWindowRef.current);

        // Create mask
        const mask = new cv.Mat();
        const lowScalar = new cv.Mat(1, 1, cv.CV_8UC3, [0, 60, 32, 255]); // [H, S, V]
        const highScalar = new cv.Mat(1, 1, cv.CV_8UC3, [180, 255, 255, 255]);
        cv.inRange(roi, lowScalar, highScalar, mask);

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
        startTracking
    };
}