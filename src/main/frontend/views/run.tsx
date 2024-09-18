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
import type Pageable from "Frontend/generated/com/vaadin/hilla/mappedtypes/Pageable";
import type Filter from "Frontend/generated/com/vaadin/hilla/crud/filter/Filter";

// Shit design requires sheet solutions
const LocalTestResultService = {...TestResultService};

/**
 * The Vaadin hilla library has  some serious problems with null or not existing values
 */
function replaceNullValues(item:any) {
    if(!item.resultText) item.resultText = "";
    return item;
}

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
            return replaceNullValues(value);
        });
    }

    LocalTestResultService.list = async (pageable: Pageable, filter: Filter | undefined, init?: EndpointRequestInit) => {
        return TestResultService.list(pageable, filter, init).then((items)=>items.map(replaceNullValues));
    }

    function startRun(){
        if(testResultData){
            alert("Stop old run first!");
        } else {
            setTestResultData(readyTestResultData);
        }
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
                    visibleFields: ['testParameter', 'description', 'resultText', 'run'],
                    fieldOptions: {
                        testParameter: {
                            renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="label" service={localTestParameterService}/>,
                        },
                        description: {
                            renderer: ({ field }) => <TextArea {...field} />,
                        },
                        resultText: {
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
