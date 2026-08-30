import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import TestResultModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestResultModel";
import {createAutoComboBoxService} from "cms/components/combobox/service";
import AutoComboBox from "cms/components/combobox/AutoComboBox";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import RunStatus from "Frontend/generated/ch/rupfizupfi/deck/data/RunStatus";
import {GridColumn, Icon, TextArea, VerticalLayout} from "@vaadin/react-components";
import {useEffect, useState} from "react";
import {useSignal} from "@vaadin/hilla-react-signals";
import {getService} from "Frontend/service/StatusService";
import {IMessage} from "@stomp/rx-stomp";
import LiveTestResult from "Frontend/components/dashboard/LiveTestResult";
import {Link} from "react-router";
import OwnerSelector from "cms/components/owner/OnwerSelector";
import {Button} from "@vaadin/react-components/Button.js";
import createEmptyValueProxy from "cms/components/owner/createEmptyValueProxy";
import {AutoCrud} from "cms/components/autocrud/AutoCrud";
import ownerGridColumn from "cms/model/owner/ownerGridColumn";
import sampleGridColumn from "cms/model/sample/sampleGridColumn";
import {TestParameterService, SampleService, TestResultService} from "Frontend/generated/endpoints";

createEmptyValueProxy(TestResultModel);

/**
 * A run that survived a sensor loss must be visibly not a clean one in the list — see RunStatus, which
 * makes COMPLETED_WITH_GAPS its own value rather than a flag beside COMPLETED for the same reason.
 * The name is rendered verbatim: it is the stored audit vocabulary, and prettifying it would leave the
 * operator and the database saying different words about the same run.
 */
const RUN_STATUS_COLOUR: Partial<Record<RunStatus, string>> = {
    [RunStatus.COMPLETED_WITH_GAPS]: '#a35b00',
    [RunStatus.ABORTED]: '#8a1f14',
    [RunStatus.FAULT]: '#8a1f14',
};

function runStatusCell(status?: RunStatus) {
    if (!status) {
        // Every run predating the recovery feature has no status, and that is not a fault.
        return <span style={{color: 'var(--lumo-secondary-text-color)'}}>&mdash;</span>;
    }

    const colour = RUN_STATUS_COLOUR[status];
    return <span style={{color: colour, fontWeight: colour ? 600 : undefined}}>{status}</span>;
}

export const config: ViewConfig = {menu: {order: 10, icon: 'line-awesome/svg/play-circle-solid.svg'}, title: 'Execute test', loginRequired: true};

export default function RunView() {
    const status = useSignal('');
    const service = getService();
    const [testResultData, setTestResultData] = useState<TestResult>();
    const [readyTestResultData, setReadyTestResultData] = useState<TestResult>();
    useEffect(() => {
        const subscription = service.loadCellObservable.subscribe((value: IMessage) => status.value = value.body);
        return () => subscription.unsubscribe();
    }, [service]);

    function startRun(){
        if(testResultData){
            alert("Stop old run first!");
        } else {
            setTestResultData(readyTestResultData);
        }
    }

    function headerRenderer(editedItem: TestResult | null, disabled: boolean) {
        if(readyTestResultData !== editedItem){
            setTimeout(setReadyTestResultData, 100, editedItem);
        }
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
                    // Explicit list: a new entity field does not appear here on its own.
                    visibleColumns: ['owner', 'testParameter', 'sample', 'description', 'runStatus', 'results', 'images', 'tracking'],
                    columnOptions: {
                        owner: ownerGridColumn,
                        testParameter: {
                            renderer: ({item}: { item: TestResult }) => item.testParameter.label
                        },
                        sample: sampleGridColumn,
                        runStatus: {
                            renderer: ({item}: { item: TestResult }) => runStatusCell(item.runStatus)
                        }
                    },
                    customColumns: [
                        <GridColumn key="results" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/result`}>Results</Link>} header="Results" autoWidth/>,
                        <GridColumn key="images" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/image`}>View</Link>} header="Bilder" autoWidth/>,
                        <GridColumn key="tracking" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/tracking`}>GoTo</Link>} header="Tracking" autoWidth/>
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
        </VerticalLayout>
    );
}
