import React from 'react';
import type {LogBuffer} from "cms/components/dashboard/logBuffer";

interface LogComponentProps {
    log: LogBuffer;
}

const LogComponent: React.FC<LogComponentProps> = ({log}) => (
    <div className="w-full lumo-space-m">
        <h2 className="lumo-typography">Logs:</h2>
        {log.hidden > 0 && (
            <p className="lumo-secondary-text-color">{log.hidden} earlier lines hidden</p>
        )}
        <ul className="lumo-primary-text">
            {log.lines.map((line) => (
                <li key={line.id} className="lumo-space-xs">{line.text}</li>
            ))}
        </ul>
    </div>
);

export default React.memo(LogComponent);
