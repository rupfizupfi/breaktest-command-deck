import type {CSSProperties, JSX} from "react";
import RunStatus from "Frontend/generated/ch/rupfizupfi/deck/data/RunStatus";

/** A run (or its record) that is incomplete but whose data still holds. */
export const RUN_WARNING_COLOUR = '#a35b00';

/** A run (or its record) that failed. */
export const RUN_FAULT_COLOUR = '#8a1f14';

/**
 * A run that did not finish clean must visibly not look clean. Exhaustive on purpose: a new
 * RunStatus member is a type error here until it is given a colour (undefined = plain text).
 */
const RUN_STATUS_COLOUR: Record<RunStatus, string | undefined> = {
    [RunStatus.IN_PROGRESS]: undefined,
    [RunStatus.COMPLETED]: undefined,
    [RunStatus.COMPLETED_WITH_GAPS]: RUN_WARNING_COLOUR,
    [RunStatus.INTERRUPTED]: RUN_WARNING_COLOUR,
    [RunStatus.ABORTED]: RUN_FAULT_COLOUR,
    [RunStatus.FAULT]: RUN_FAULT_COLOUR,
};

export function runStatusStyle(status?: RunStatus): CSSProperties {
    const colour = status ? RUN_STATUS_COLOUR[status] : undefined;
    return colour ? {color: colour, fontWeight: 600} : {};
}

/**
 * The name is rendered verbatim so the operator and the stored audit vocabulary use the same
 * words, and COMPLETED_WITH_GAPS cannot be read as a clean run.
 */
export function RunStatusText({status, missing = '—'}: {status?: RunStatus, missing?: string}): JSX.Element {
    if (!status) {
        // Every run predating the recovery feature has no status, and that is not a fault.
        return <span style={{color: 'var(--lumo-secondary-text-color)'}}>{missing}</span>;
    }
    return <span style={runStatusStyle(status)}>{status}</span>;
}
