import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {TestResultService} from "Frontend/generated/endpoints";
import {useParams} from "react-router-dom";
import {useEffect, useState} from "react";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";
import {constraintServiceToFilter} from "Frontend/util/service";
import Placeholder from "Frontend/components/placeholder/Placeholder";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import ResultViewer from "Frontend/components/dashboard/ResultViewer";

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg', exclude: true}, title: 'Result', loginRequired: true};

export default function SampleView() {
    const {resultId} = useParams();
    const [result, setResult] = useState<TestResult>();
    const [localService, setLocalService] = useState(TestResultService);

    useEffect(() => {
        if (resultId) {
            TestResultService.get(parseFloat(resultId)).then(setResult);
            setLocalService(constraintServiceToFilter(TestResultService, {
                propertyId: 'test_result.id',
                filterValue: resultId,
                matcher: Matcher.EQUALS,
                '@type': 'propertyNumber',
            }));
        }
    }, [resultId]);

    if (!localService) {
        return (<Placeholder/>);
    }

    if (!result) {
        return (<div className="flex flex-col h-full items-center justify-center p-l text-center box-border">
            <img style={{width: '200px'}} src="images/empty-plant.png"/>
            <h2>Result with id "{resultId}" doesn't exist</h2>
        </div>)
    }

    return (
        <ResultViewer testResult={result}/>
    );
}
