import React, {useEffect, useRef, useState} from "react";
import Webcam from "react-webcam";
import cv from "@techstark/opencv-js";
import useCamShiftTracking from "Frontend/components/webcam/tracking/CamShiftTracking";

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
    const comShiftTracking = useCamShiftTracking();

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
            setCalibrationPoints([...calibrationPoints, {x, y}]);
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
            if (canvasRef.current) {
                comShiftTracking.startTracking(x, y, canvas.getContext("2d")!, canvasRef.current);
            }
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
                    comShiftTracking.processFrames(canvas, scaleFactor);
                };
            }
        }
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
            <div style={{position: "relative", width: 640, height: 480}}>
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