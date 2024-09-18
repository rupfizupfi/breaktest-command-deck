import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import {TestParameterService, TestResultService} from "Frontend/generated/endpoints";
import TestResultModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestResultModel";
import {createAutoComboBoxService} from "Frontend/components/combobox/service";
import AutoComboBox from "Frontend/components/combobox/AutoComboBox";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import {GridColumn, TextArea, VerticalLayout} from "@vaadin/react-components";
import React, {useState} from "react";
import {useSignal} from "@vaadin/hilla-react-signals";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import {EndpointRequestInit} from "@vaadin/hilla-frontend/Connect.js";
import TestResultBoard from "Frontend/components/dashboard/TestResultBoard";
import {Link} from "react-router-dom";

// Shit design requires sheet solutions
const LocalTestResultService = {...TestResultService};

export const config: ViewConfig = {menu: {order: 10, icon: 'line-awesome/svg/play-circle-solid.svg'}, title: 'Ausführen', loginRequired: true};

export default function RunView() {
    const status = useSignal('');
    const service = getService();
    const [testResultData, setTestResultData] = useState<TestResult>();
    const [readyTestResultData, setReadyTestResultData] = useState<TestResult>();
    service.updateObservable.subscribe((value: IMessage) => status.value = value.body);

    LocalTestResultService.save = async (entity: TestResult, init: EndpointRequestInit | undefined) => {
        return TestResultService.save(entity, init).then((value) => {
            setReadyTestResultData(value);
            return value;
        });
    }

    function startRun(){
        setTestResultData(readyTestResultData);
    }

    const localTestParameterService = createAutoComboBoxService(TestParameterService, ["type", "sample.name"]);

    return (
        <VerticalLayout theme="spacing-l stretch evenly">
            <AutoCrud
                className={'w-full'}
                service={LocalTestResultService}
                model={TestResultModel}
                gridProps={{
                    visibleColumns: ['testParameter', 'description', 'results'],
                    columnOptions: {
                        testParameter: {
                            renderer: ({item}: { item: TestResult }) => item.testParameter.label
                        },
                    },
                    customColumns: [
                        <GridColumn key="results" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/result`}>Results</Link>} header="Results" autoWidth/>
                    ]
                }}
                formProps={{
                    visibleFields: ['testParameter', 'description', 'run'],
                    fieldOptions: {
                        testParameter: {
                            renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="label" service={localTestParameterService}/>,
                        },
                        description: {
                            renderer: ({ field }) => <TextArea {...field} />,
                        },
                        comment: {
                            renderer: ({ field }) => <TextArea {...field} />,
                        },
                        run: {
                            renderer: () => <button disabled={!readyTestResultData} onClick={() => startRun()}>Run test</button>,
                        },
                    }
                }}
            />
            {testResultData && <TestResultBoard testResult={testResultData} reset={() => setTestResultData(undefined)}/>}
        </VerticalLayout>
    );
}
