import React from 'react';
import {Button} from '@vaadin/react-components/Button.js';

interface ErrorBoundaryProps {
    children: React.ReactNode;
    /** Names the region in the console and the fallback, e.g. "This page" or "The chart". */
    region: string;
    /** A change clears a caught error, e.g. the pathname, so navigating away recovers. */
    resetKey?: unknown;
}

interface ErrorBoundaryState {
    error: Error | null;
}

/**
 * Contains a render error to the region it wraps, so what sits outside it — the drawer, Stop, the
 * incident banner — stays mounted. React offers this only as a class component.
 */
export default class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
    state: ErrorBoundaryState = {error: null};

    static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
        return {error: error instanceof Error ? error : new Error(String(error))};
    }

    componentDidCatch(error: unknown, info: React.ErrorInfo) {
        console.error(`${this.props.region} failed to render`, error, info.componentStack);
    }

    componentDidUpdate(previous: ErrorBoundaryProps) {
        if (this.state.error !== null && !Object.is(previous.resetKey, this.props.resetKey)) {
            this.setState({error: null});
        }
    }

    render() {
        const {error} = this.state;
        if (error === null) {
            return this.props.children;
        }

        return (
            <div role="alert" className="p-m" style={{color: 'var(--lumo-error-text-color)'}}>
                <p style={{fontWeight: 600, margin: 0}}>{this.props.region} failed to render: {error.message}</p>
                <Button theme="tertiary" onClick={() => this.setState({error: null})}>Try again</Button>
            </div>
        );
    }
}
