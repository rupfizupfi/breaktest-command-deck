export interface SelectedArea {
    x: number;
    y: number;
    width: number;
    height: number;
}

export default function useAreaSelector(canvas: HTMLCanvasElement, callback: (selection: SelectedArea) => void) {
    const ctx = canvas.getContext('2d')!;
    const selection = { x: 0, y: 0, width: 0, height: 0 };
    let isSelecting = false;

    function handleMouseDown(event: MouseEvent) {
        isSelecting = true;
        const rect = canvas.getBoundingClientRect();
        selection.x = event.clientX - rect.left;
        selection.y = event.clientY - rect.top;
        selection.width = 0;
        selection.height = 0;
    }

    function handleMouseMove(event: MouseEvent) {
        if (!isSelecting) {
            return;
        }
        const rect = canvas.getBoundingClientRect();
        selection.width = event.clientX - rect.left - selection.x;
        selection.height = event.clientY - rect.top - selection.y;
        drawSelection();
    }

    function handleMouseUp() {
        isSelecting = false;
        callback(selection);
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