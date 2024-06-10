import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import {TestParameterService, TestResultService} from "Frontend/generated/endpoints";
import TestResultModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestResultModel";
import {createAutoComboBoxService} from "Frontend/components/combobox/service";
import AutoComboBox from "Frontend/components/combobox/AutoComboBox";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import { VerticalLayout} from "@vaadin/react-components";
import React, {useState} from "react";
import {useSignal} from "@vaadin/hilla-react-signals";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import {EndpointRequestInit} from "@vaadin/hilla-frontend/Connect.js";
import TestResultBoard from "Frontend/components/dashboard/TestResultBoard";

// Shit design requires sheet solutions
const LocalTestResultService = {...TestResultService};

export const config: ViewConfig = {menu: {order: 10, icon: 'line-awesome/svg/play-circle-solid.svg'}, title: 'Ausführen', loginRequired: true};

export default function RunView() {
    const status = useSignal('');
    const service = getService();
    const [testResultData, setTestResultData] = useState<TestResult>();
    service.updateObservable.subscribe((value: IMessage) => status.value = value.body);

    LocalTestResultService.save = async (entity: TestResult, init:EndpointRequestInit|undefined) => {
        return TestResultService.save(entity,init).then((value) => {
            setTestResultData(value);
            return value;
        });
    }

    const localTestParameterService = createAutoComboBoxService(TestParameterService, ["type", "sample.name"]);

    return (
        <VerticalLayout theme="spacing-l stretch evenly">
            <AutoCrud
                className={'w-full'}
                service={LocalTestResultService}
                model={TestResultModel}
                gridProps={{
                    columnOptions: {
                        testParameter: {
                            renderer: ({item}: { item: TestResult }) => item.testParameter.label
                        }
                    }
                }}
                formProps={{
                    visibleFields: ['testParameter', 'description'],
                    fieldOptions: {
                        testParameter: {
                            renderer: ({field}) => <AutoComboBox {...field} itemIdPath="id" itemValuePath="id" itemLabelPath="label" service={localTestParameterService}/>,
                        }
                    },
                }}
            />
            {testResultData && <TestResultBoard testResult={testResultData} reset={()=>setTestResultData(undefined)} />}
        </VerticalLayout>
    );
}
