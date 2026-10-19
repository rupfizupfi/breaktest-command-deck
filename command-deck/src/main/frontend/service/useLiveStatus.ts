import {useEffect, useState} from "react";
import {DISCONNECTED_LIVE_STATUS, getService, LiveStatus} from "Frontend/service/StatusService";

/**
 * Subscribes a component to feed freshness. The service emits only on change: no re-renders for a
 * healthy feed, one per second for a stale one.
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
