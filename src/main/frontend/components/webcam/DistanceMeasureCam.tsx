import React, { useEffect, useRef, useState } from "react";
import Webcam from "react-webcam";
import cv, {Point2f} from "@techstark/opencv-js";

interface CalibrationPoint {
    x: number;
    y: number;
}

const FACING_MODE_USER = "user";
const FACING_MODE_ENVIRONMENT = "environment";

export default function DistanceMeasureCam() {
    const webcamRef = useRef<Webcam>(null);
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const [isCalibrating, setIsCalibrating] = useState(false);
    const [calibrationPoints, setCalibrationPoints] = useState<CalibrationPoint[]>([]);
    const [scaleFactor, setScaleFactor] = useState(1);
    const [facingMode, setFacingMode] = useState(FACING_MODE_USER);

    // CAMShift-related refs
    const trackingWindowRef = useRef<cv.Rect | null>(null);
    const histRef = useRef<cv.Mat | null>(null);
    const trackingPointRef = useRef<CalibrationPoint | null>(null); // To calculate movement

    const videoConstraints: MediaTrackConstraints = {
        facingMode: facingMode,
        width: 640,
        height: 480,
    };

    useEffect(() => {
        // Wait for OpenCV to be ready
        cv['onRuntimeInitialized'] = () => {
            function update() {
                captureFrame();
                requestAnimationFrame(update);
            }
            update();
        };
    }, []);

    const handleCanvasClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
        const canvas = canvasRef.current!;
        const rect = canvas.getBoundingClientRect();
        const x = ((e.clientX - rect.left) * canvas.width) / rect.width;
        const y = ((e.clientY - rect.top) * canvas.height) / rect.height;

        if (isCalibrating) {
            setCalibrationPoints([...calibrationPoints, { x, y }]);
            if (calibrationPoints.length === 1) {
                // Two points selected
                const dx = x - calibrationPoints[0].x;
                const dy = y - calibrationPoints[0].y;
                const pixelDistance = Math.sqrt(dx * dx + dy * dy);
                const realDistance = prompt("Enter the real-world distance between the two points (in cm):");
                if (realDistance && !isNaN(parseFloat(realDistance))) {
                    setScaleFactor(pixelDistance / parseFloat(realDistance));
                    setIsCalibrating(false);
                    setCalibrationPoints([]);
                } else {
                    alert("Invalid input. Please enter a numeric value.");
                }
            }
        } else {
            // Initialize tracking window and histogram
            const windowSize = 10; // Adjust as needed
            const startX = Math.max(0, x - windowSize / 2);
            const startY = Math.max(0, y - windowSize / 2);
            const width = Math.min(windowSize, canvas.width - startX);
            const height = Math.min(windowSize, canvas.height - startY);
            trackingWindowRef.current = new cv.Rect(startX, startY, width, height);

            // Capture the template and calculate histogram
            if (canvasRef.current) {
                const ctx = canvasRef.current.getContext("2d")!;
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

            // Reset tracking point for movement calculation
            trackingPointRef.current = null;
        }
    };

    const captureFrame = () => {
        if (webcamRef.current && canvasRef.current) {
            const imageSrc = webcamRef.current.getScreenshot();
            if (imageSrc) {
                const img = new Image();
                img.src = imageSrc;
                img.onload = () => {
                    const canvas = canvasRef.current!;
                    const ctx = canvas.getContext("2d")!;
                    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
                    processFrame(ctx);
                };
            }
        }
    };

    const processFrame = (ctx: CanvasRenderingContext2D) => {
        const canvas = canvasRef.current!;
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
            trackingPointRef.current = { x: currentCenter.x, y: currentCenter.y };

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
    };

    return (
        <div>
            <button onClick={() => setIsCalibrating(true)}>Calibrate</button>
            <button
                onClick={() =>
                    setFacingMode(
                        facingMode === FACING_MODE_ENVIRONMENT ? FACING_MODE_USER : FACING_MODE_ENVIRONMENT
                    )
                }
            >
                Change cam
            </button>
            <p>Click on the image to select an object to track.</p>
            <div style={{ position: "relative", width: 640, height: 480 }}>
                <Webcam
                    ref={webcamRef}
                    screenshotFormat="image/jpeg"
                    width={640}
                    height={480}
                    videoConstraints={videoConstraints}
                    style={{
                        position: "absolute",
                        top: 0,
                        left: 0,
                        opacity: 0,
                        pointerEvents: "none",
                    }}
                />
                <canvas
                    ref={canvasRef}
                    width={640}
                    height={480}
                    onClick={handleCanvasClick}
                />
            </div>
        </div>
    );
}