import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {SampleService, TestParameterService, TestResultService} from "Frontend/generated/endpoints";
import TestResultModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestResultModel";
import {createAutoComboBoxService} from "Frontend/components/combobox/service";
import AutoComboBox from "Frontend/components/combobox/AutoComboBox";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import {GridColumn, Icon, TextArea, VerticalLayout} from "@vaadin/react-components";
import React, {useState} from "react";
import {useSignal} from "@vaadin/hilla-react-signals";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import LiveTestResult from "Frontend/components/dashboard/LiveTestResult";
import {Link} from "react-router-dom";
import OwnerSelector from "Frontend/components/owner/OnwerSelector";
import {Button} from "@vaadin/react-components/Button.js";
import createEmptyValueProxy from "Frontend/components/owner/createEmptyValueProxy";
import {AutoCrud} from "Frontend/components/autocrud/AutoCrud";
import ownerGridColumn from "Frontend/model/owner/ownerGridColumn";
import sampleGridColumn from "Frontend/model/sample/sampleGridColumn";
import DistanceMeasureCam from "Frontend/components/webcam/DistanceMeasureCam";

createEmptyValueProxy(TestResultModel);

export const config: ViewConfig = {menu: {order: 10, icon: 'line-awesome/svg/play-circle-solid.svg'}, title: 'Execute test', loginRequired: true};

export default function RunView() {
    const status = useSignal('');
    const service = getService();
    const [testResultData, setTestResultData] = useState<TestResult>();
    const [readyTestResultData, setReadyTestResultData] = useState<TestResult>();
    service.loadCellObservable.subscribe((value: IMessage) => status.value = value.body);

    function startRun(){
        if(testResultData){
            alert("Stop old run first!");
        } else {
            setTestResultData(readyTestResultData);
        }
    }

    function headerRenderer(editedItem: TestResult | null, disabled: boolean) {
        setTimeout(setReadyTestResultData, 0, editedItem);
        const colorVar = disabled ? 'var(--lumo-disabled-text-color)' : 'var(--lumo-text-color)';
        // @ts-ignore
        return <h3 style={{ color: colorVar }}>{editedItem ? (editedItem.__copy?'Copy' :'Edit' ): 'New'} item</h3>;
    }

    const localTestParameterService = createAutoComboBoxService(TestParameterService, "type");
    const localSampleService = createAutoComboBoxService(SampleService, "name");

    return (
        <VerticalLayout theme="spacing-l stretch evenly h-full min-h-full">
            <AutoCrud
                className="w-full h-full min-h-full"
                service={TestResultService}
                model={TestResultModel}
                gridProps={{
                    visibleColumns: ['owner', 'testParameter', 'sample', 'description', 'results', 'images'],
                    columnOptions: {
                        owner: ownerGridColumn,
                        testParameter: {
                            renderer: ({item}: { item: TestResult }) => item.testParameter.label
                        },
                        sample: sampleGridColumn
                    },
                    customColumns: [
                        <GridColumn key="results" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/result`}>Results</Link>} header="Results" autoWidth/>,
                        <GridColumn key="images" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/image`}>Results</Link>} header="Bilder" autoWidth/>
                    ]
                }}
                formProps={{
                    headerRenderer,
                    visibleFields: ['owner', 'testParameter', 'sample', 'description', 'resultText', 'run', 'images'],
                    fieldOptions: {
                        owner: {
                            renderer: ({field}) => <OwnerSelector {...field} />,
                        },
                        testParameter: {
                            renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="label" service={localTestParameterService}/>,
                        },
                        sample: {
                            renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="name" service={localSampleService}/>,
                        },
                        description: {
                            renderer: ({field}) => <TextArea {...field} />,
                        },
                        resultText: {
                            renderer: ({field}) => <TextArea {...field} />,
                        },
                        run: {
                            renderer: () => <Button theme="pirmary large icon" style={{marginTop:'1em'}} disabled={!readyTestResultData} onClick={() => startRun()}>
                                <Icon icon="vaadin:bolt" slot={'prefix'} style={{ height: 'var(--lumo-icon-size-l)', width: 'var(--lumo-icon-size-l)' }} />
                                Run test
                            </Button>,
                        }
                    }
                }}
            />
            <LiveTestResult testResult={testResultData} reset={() => setTestResultData(undefined)}/>
            <DistanceMeasureCam/>
        </VerticalLayout>
    );
}
