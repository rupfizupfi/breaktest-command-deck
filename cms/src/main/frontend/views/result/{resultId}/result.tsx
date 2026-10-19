import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {TestResultService} from "Frontend/generated/endpoints";
import {useParams} from "react-router";
import {useEffect, useState} from "react";
import Placeholder from "cms/components/placeholder/Placeholder";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import ResultViewer from "cms/components/dashboard/ResultViewer";
import {Load, parseRouteId, settleLoad} from "cms/util/load";

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg', exclude: true}, title: 'Result', loginRequired: true};

export default function SampleView() {
    const {resultId} = useParams();
    const [load, setLoad] = useState<Load<TestResult>>({kind: 'loading'});

    useEffect(() => {
        const id = parseRouteId(resultId);
        if (id === undefined) {
            setLoad({kind: 'not-found'});
            return;
        }

        // A slow answer for the previous id must not replace the current one.
        let cancelled = false;
        setLoad({kind: 'loading'});
        // The rpc middleware toasts a failure's cause; settleLoad turns it into the error state.
        settleLoad(TestResultService.get(id)).then((next) => {
            if (!cancelled) {
                setLoad(next);
            }
        });
        return () => {
            cancelled = true;
        };
    }, [resultId]);

    switch (load.kind) {
        case 'loading':
            return <Placeholder/>;
        case 'found':
            return <ResultViewer testResult={load.value}/>;
        case 'not-found':
        case 'error':
            return (
                <div className="flex flex-col h-full items-center justify-center p-l text-center box-border">
                    {/* Decorative: the heading below says what happened. */}
                    <img style={{width: '200px'}} src="images/empty-plant.png" alt=""/>
                    <h2>{load.kind === 'not-found'
                        ? `Result with id "${resultId}" doesn't exist`
                        : `Result "${resultId}" could not be loaded`}</h2>
                </div>
            );
    }
}
