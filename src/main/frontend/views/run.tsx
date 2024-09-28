import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
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
import {EndpointRequestInit} from "@vaadin/hilla-frontend/Connect.js";
import TestResultBoard from "Frontend/components/dashboard/TestResultBoard";
import {Link} from "react-router-dom";
import OwnerSelector from "Frontend/components/owner/OnwerSelector";
import {OwnerGridView} from "Frontend/components/owner/OwnerGridView";
import {Button} from "@vaadin/react-components/Button.js";
import createEmptyValueProxy from "Frontend/components/owner/createEmptyValueProxy";

// Shit design requires sheet solutions
const LocalTestResultService = {...TestResultService};


createEmptyValueProxy(TestResultModel);

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
        if(testResultData){
            alert("Stop old run first!");
        } else {
            setTestResultData(readyTestResultData);
        }
    }

    function headerRenderer(editedItem: TestResult | null, disabled: boolean) {
        setTimeout(setReadyTestResultData, 0, editedItem);
        const colorVar = disabled ? 'var(--lumo-disabled-text-color)' : 'var(--lumo-text-color)';
        return <h3 style={{ color: colorVar }}>{editedItem ? 'Edit item' : 'New item'}</h3>;
    }

    const localTestParameterService = createAutoComboBoxService(TestParameterService, "type");
    const localSampleService = createAutoComboBoxService(SampleService, "name");

    return (
        <VerticalLayout theme="spacing-l stretch evenly h-full min-h-full">
            <AutoCrud
                className="w-full h-full min-h-full"
                service={LocalTestResultService}
                model={TestResultModel}
                gridProps={{
                    visibleColumns: ['owner', 'testParameter', 'sample', 'description', 'results'],
                    columnOptions: {
                        owner: {
                            renderer: OwnerGridView
                        },
                        testParameter: {
                            renderer: ({item}: { item: TestResult }) => item.testParameter.label
                        },
                        sample: {
                            renderer: ({item}: { item: TestResult }) => item.sample?.name
                        }
                    },
                    customColumns: [
                        <GridColumn key="results" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/result`}>Results</Link>} header="Results" autoWidth/>
                    ]
                }}
                formProps={{
                    headerRenderer,
                    visibleFields: ['owner', 'testParameter', 'sample', 'description', 'resultText', 'run'],
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
            {testResultData && <TestResultBoard testResult={testResultData} reset={() => setTestResultData(undefined)}/>}
        </VerticalLayout>
    );
}
