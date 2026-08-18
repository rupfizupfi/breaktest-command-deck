import React from "react";
import {FeedStatus} from "Frontend/service/StatusService";
import './StaleValue.css';

/** "8 s ago" reads unambiguously; a dimmed number on its own still invites being read as current. */
export function formatAge(seconds: number | null): string {
    if (seconds === null) {
        return '';
    }

    if (seconds < 60) {
        return `${seconds} s`;
    }

    const minutes = Math.floor(seconds / 60);
    const rest = seconds % 60;
    return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
}

export interface StaleValueProps {
    status: FeedStatus;
    /** False when this component has no reading of its own yet, even if the topic is alive. */
    hasValue?: boolean;
    children: React.ReactNode;
}

/**
 * Renders a live value, or makes it unmistakable that the number is not live.
 *
 * A steady force and a dead load cell both render as an unchanging number, so the only honest
 * display is one that states the age. Never shows a stale reading as though it were current.
 */
export default function StaleValue({status, hasValue = true, children}: StaleValueProps): React.JSX.Element {
    if (!hasValue || !status.everReceived) {
        return <span className="stale-value stale-value--unknown" title="no data received yet">&mdash;</span>;
    }

    if (!status.stale) {
        return <>{children}</>;
    }

    return (
        <span className="stale-value stale-value--stale" title="this value is not live">
            {children} <span className="stale-value__age">({formatAge(status.staleForSeconds)} ago)</span>
        </span>
    );
}
