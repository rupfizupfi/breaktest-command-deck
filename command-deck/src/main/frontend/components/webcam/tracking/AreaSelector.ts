export interface SelectedArea {
    x: number;
    y: number;
    width: number;
    height: number;
}

function clampRect(r: SelectedArea, w: number, h: number): SelectedArea {
    const x = Math.max(0, Math.min(r.x, w - 1));
    const y = Math.max(0, Math.min(r.y, h - 1));
    const maxW = w - x, maxH = h - y;
    const width = Math.max(1, Math.min(r.width, maxW));
    const height = Math.max(1, Math.min(r.height, maxH));
    return {x,y, width, height};
}

export default function useAreaSelector(canvas: HTMLCanvasElement, callback: (selection: SelectedArea) => void) {
    const ctx = canvas.getContext('2d', {willReadFrequently:true, alpha:false, colorSpace: 'srgb', colorType: 'unorm8', desynchronized: true}) as CanvasRenderingContext2D;
    const start = { x: 0, y: 0 };
    // Normalised against the press point, so a drag in any direction yields a positive size.
    const selection: SelectedArea = { x: 0, y: 0, width: 0, height: 0 };
    let isSelecting = false;

    function handleMouseDown(event: MouseEvent) {
        isSelecting = true;
        const rect = canvas.getBoundingClientRect();
        start.x = event.clientX - rect.left;
        start.y = event.clientY - rect.top;
        selection.x = start.x;
        selection.y = start.y;
        selection.width = 0;
        selection.height = 0;
    }

    function handleMouseMove(event: MouseEvent) {
        if (!isSelecting) {
            return;
        }
        const rect = canvas.getBoundingClientRect();
        const currentX = event.clientX - rect.left;
        const currentY = event.clientY - rect.top;
        selection.x = Math.min(start.x, currentX);
        selection.y = Math.min(start.y, currentY);
        selection.width = Math.abs(currentX - start.x);
        selection.height = Math.abs(currentY - start.y);
        drawSelection();
    }

    function handleMouseUp() {
        isSelecting = false;
        const result = clampRect(selection, canvas.width, canvas.height);
        callback(result);
    }

    function drawSelection() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.strokeStyle = 'red';
        ctx.lineWidth = 2;
        ctx.strokeRect(selection.x, selection.y, selection.width, selection.height);
    }

    canvas.addEventListener('mousedown', handleMouseDown);
    canvas.addEventListener('mousemove', handleMouseMove);
    canvas.addEventListener('mouseup', handleMouseUp);

    return {
        removeEventListeners: () => {
            canvas.removeEventListener('mousedown', handleMouseDown);
            canvas.removeEventListener('mousemove', handleMouseMove);
            canvas.removeEventListener('mouseup', handleMouseUp);
        },
    };
}
