import React from 'react';

interface LogComponentProps {
    logs: string[];
}

const LogComponent: React.FC<LogComponentProps> = ({logs}) => (
    <div className="w-full lumo-space-m">
        <h2 className="lumo-typography">Logs:</h2>
        <ul className="lumo-primary-text">
            {logs.map((log, index) => (
                <li key={index} className="lumo-space-xs">{log}</li>
            ))}
        </ul>
    </div>
);

export default React.memo(LogComponent);