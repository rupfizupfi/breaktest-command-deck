import React from "react";
import {FeedStatus} from "Frontend/service/StatusService";
import './StaleValue.css';

/** Formats an age for "8 s ago"-style labels; a dimmed number alone still reads as current. */
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
 * Renders a live value, or marks it with its age: a steady force and a dead load cell otherwise
 * look the same.
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
