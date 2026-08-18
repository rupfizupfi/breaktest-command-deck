import {useEffect, useState} from "react";
import {DISCONNECTED_LIVE_STATUS, getService, LiveStatus} from "Frontend/service/StatusService";

/**
 * Subscribes a component to feed freshness. Kept out of StatusService so the service itself stays
 * free of React.
 *
 * The service only emits when the displayed state actually changes, so a healthy feed costs no
 * re-renders and a stale one costs one per second.
 */
export function useLiveStatus(): LiveStatus {
    const service = getService();
    const [status, setStatus] = useState<LiveStatus>(() => service.currentLiveStatus);

    useEffect(() => {
        const subscription = service.liveStatus.subscribe(setStatus);
        return () => subscription.unsubscribe();
    }, [service]);

    return status;
}

export {DISCONNECTED_LIVE_STATUS};
