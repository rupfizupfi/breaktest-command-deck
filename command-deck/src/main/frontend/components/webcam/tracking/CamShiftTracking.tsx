import {CV, Mat, Rect} from "@techstark/opencv-js";
import useAreaSelector, {SelectedArea} from "Frontend/components/webcam/tracking/AreaSelector";

function getContext(canvas: HTMLCanvasElement): CanvasRenderingContext2D {
    return canvas.getContext("2d", {willReadFrequently: true, alpha: false, colorSpace: 'srgb', colorType: 'unorm8', desynchronized: true}) as CanvasRenderingContext2D;
}

export default function creatCamshiftTracking(cv: CV) {
    let trackingWindow: Rect | null = null;
    let trackingPoint: { x: number, y: number } | null = null;
    let roiHist: Mat | null = null;

    const smoothBackProj = true;
    const termCriteria = new cv.TermCriteria(cv.TermCriteria_EPS | cv.TermCriteria_COUNT, 10, 1);
    const hsvFrame = new cv.Mat();
    const kernel = cv.Mat.ones(3, 3, cv.CV_8U);

    function init(canvas: HTMLCanvasElement) {
        const areaSelector = useAreaSelector(canvas, (selection) => {
            startTracking(selection, getContext(canvas), canvas);
        });

        return () => {
            areaSelector.removeEventListeners();
            if (roiHist) {
                roiHist.delete();
                roiHist = null;
            }
            hsvFrame.delete();
            kernel.delete();
        };
    }

    function startTracking(selection: SelectedArea, ctx: CanvasRenderingContext2D, canvas: HTMLCanvasElement) {
        trackingWindow = new cv.Rect(selection.x, selection.y, selection.width, selection.height);

        if (selection.width <= 4 || selection.height <= 4) {
            console.error("Invalid tracking window dimensions:", trackingWindow);
            trackingWindow = null;
            return;
        }

        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const frame = cv.matFromImageData(imageData);

        // Define the region of interest (ROI)
        const rawRoi = frame.roi(trackingWindow);
        const hsvRoi = new cv.Mat();
        const mask = new cv.Mat();
        const hist = new cv.Mat();
        const hsvRoiVector = new cv.MatVector();
        let low: Mat | null = null;
        let high: Mat | null = null;

        try {
            cv.cvtColor(rawRoi, hsvRoi, cv.COLOR_RGBA2RGB);
            cv.cvtColor(hsvRoi, hsvRoi, cv.COLOR_RGB2HSV);

            // Create mask
            const lowScalar = new cv.Scalar(30, 30, 0);
            const highScalar = new cv.Scalar(180, 180, 180);
            low = new cv.Mat(hsvRoi.rows, hsvRoi.cols, hsvRoi.type(), lowScalar);
            high = new cv.Mat(hsvRoi.rows, hsvRoi.cols, hsvRoi.type(), highScalar);
            cv.inRange(hsvRoi, low, high, mask);

            // Calculate histogram
            hsvRoiVector.push_back(hsvRoi);
            cv.calcHist(hsvRoiVector, [0], mask, hist, [180], [0, 180]);
            cv.normalize(hist, hist, 0, 255, cv.NORM_MINMAX);

            if (roiHist) {
                roiHist.delete();
                roiHist = null;
            }

            if (hist.empty() || hist.rows === 0 || hist.cols === 0) {
                console.warn('Selected area has no trackable colour histogram; select again');
                return;
            }

            roiHist = hist;
        } finally {
            frame.delete();
            rawRoi.delete();
            hsvRoi.delete();
            hsvRoiVector.delete();
            mask.delete();
            low?.delete();
            high?.delete();
            // hist survives only once it is the tracked histogram.
            if (roiHist !== hist) {
                hist.delete();
            }
        }
    }

    function processFrames(canvas: HTMLCanvasElement, scaleFactor: number) {
        const ctx = getContext(canvas);
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        let realMovement = 0;
        let frame, backProj, hsvVector;

        try {
            frame = cv.matFromImageData(imageData);

            if (roiHist && trackingWindow) {
                cv.cvtColor(frame, hsvFrame, cv.COLOR_RGBA2RGB);
                cv.cvtColor(hsvFrame, hsvFrame, cv.COLOR_RGB2HSV);

                // Calculate back projection
                backProj = new cv.Mat();
                hsvVector = new cv.MatVector();
                hsvVector.push_back(hsvFrame);
                cv.calcBackProject(hsvVector, [0], roiHist, backProj, [0, 180], 1);

                if (smoothBackProj) {
                    // Light blur + morphology to reduce speckle and improve convergence
                    cv.GaussianBlur(backProj, backProj, new cv.Size(3, 3), 0);
                    cv.morphologyEx(backProj, backProj, cv.MORPH_OPEN, kernel);
                }

                // @ts-ignore
                const [trackBox, newTrackingWindow] = cv.CamShift(backProj, trackingWindow, termCriteria);

                // @ts-ignore
                let pts = cv.rotatedRectPoints(trackBox);
                cv.line(frame, pts[0], pts[1], [255, 0, 0, 255], 3);
                cv.line(frame, pts[1], pts[2], [255, 0, 0, 255], 3);
                cv.line(frame, pts[2], pts[3], [255, 0, 0, 255], 3);
                cv.line(frame, pts[3], pts[0], [255, 0, 0, 255], 3);

                const cx = trackBox.center.x, cy = trackBox.center.y;
                if (trackingPoint) {
                    const dx = cx - trackingPoint.x;
                    const dy = cy - trackingPoint.y;
                    realMovement = Math.hypot(dx, dy) / scaleFactor;
                }
                trackingPoint = {x: cx, y: cy};
                trackingWindow = newTrackingWindow;
            }

            // Display the result
            cv.imshow(canvas, frame);

            if (realMovement > 0) {
                // Display movement
                ctx.font = "20px Arial";
                ctx.fillStyle = "blue";
                ctx.fillText(`Movement: ${realMovement.toFixed(2)} cm`, 10, 30);
            }
        } catch (error) {
            console.error("Error processing frames:", error);
        } finally {
            frame?.delete();
            backProj?.delete();
            hsvVector?.delete();
        }
    }

    return {
        processFrames,
        init
    };
}
