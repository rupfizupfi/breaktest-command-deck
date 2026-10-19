import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import TestResultModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestResultModel";
import {AutoComboService, createAutoComboBoxService} from "cms/components/combobox/service";
import type Pageable from "Frontend/generated/com/vaadin/hilla/mappedtypes/Pageable";
import type TestParameter from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameter";
import AutoComboBox from "cms/components/combobox/AutoComboBox";
import TestResult from "Frontend/generated/ch/rupfizupfi/deck/data/TestResult";
import {GridColumn, Icon, TextArea, VerticalLayout} from "@vaadin/react-components";
import {useState} from "react";
import LiveTestResult from "Frontend/components/dashboard/LiveTestResult";
import {Link} from "react-router";
import OwnerSelector from "cms/components/owner/OwnerSelector";
import {Button} from "@vaadin/react-components/Button.js";
import createEmptyValueProxy from "cms/components/owner/createEmptyValueProxy";
import CurrentUserGate from "cms/components/owner/CurrentUserGate";
import {AutoCrud, AutoCrudEditState, WithCopyFlag} from "cms/components/autocrud/AutoCrud";
import {RunStatusText} from "cms/model/runStatus";
import ownerGridColumn from "cms/model/owner/ownerGridColumn";
import sampleGridColumn from "cms/model/sample/sampleGridColumn";
import {TestParameterService, SampleService, TestResultService} from "Frontend/generated/endpoints";

createEmptyValueProxy(TestResultModel);

export const config: ViewConfig = {menu: {order: 10, icon: 'line-awesome/svg/play-circle-solid.svg'}, title: 'Execute test', loginRequired: true};

export default function RunView() {
    const [testResultData, setTestResultData] = useState<TestResult>();
    const [editState, setEditState] = useState<AutoCrudEditState<TestResult>>({item: null, dirty: false});
    // start() runs the stored record, so only a saved item the form has not diverged from may run.
    const readyTestResultData = editState.item?.id != null && !editState.dirty ? editState.item : undefined;

    function startRun(){
        if(testResultData){
            alert("Stop old run first!");
        } else {
            setTestResultData(readyTestResultData);
        }
    }

    function headerRenderer(editedItem: WithCopyFlag<TestResult> | null, disabled: boolean) {
        const colorVar = disabled ? 'var(--lumo-disabled-text-color)' : 'var(--lumo-text-color)';
        return <h3 style={{ color: colorVar }}>{editedItem ? (editedItem.__copy?'Copy' :'Edit' ): 'New'} item</h3>;
    }

    // No server-side search: `type` is an enum, which Hilla filters by EQUALS only, so a typed
    // fragment would be refused. The combo box filters these on the label as the operator types.
    const localTestParameterService: AutoComboService<TestParameter> =
        () => TestParameterService.list({pageSize: 100} as Pageable, undefined);
    const localSampleService = createAutoComboBoxService(SampleService, "name");

    return (
        <VerticalLayout theme="spacing-l stretch evenly">
            <CurrentUserGate>
                <AutoCrud
                    className="w-full min-h-full"
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
                                renderer: ({item}: { item: TestResult }) => <RunStatusText status={item.runStatus}/>
                            }
                        },
                        customColumns: [
                            <GridColumn key="results" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/result`}>Results</Link>} header="Results" autoWidth/>,
                            <GridColumn key="images" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/image`}>View</Link>} header="Images" autoWidth/>,
                            <GridColumn key="tracking" renderer={({item}: { item: TestResult }) => <Link to={`/result/${item.id}/tracking`}>GoTo</Link>} header="Tracking" autoWidth/>
                        ]
                    }}
                    onEditStateChange={setEditState}
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
                                renderer: () => <Button theme="primary large icon" style={{marginTop:'1em'}} disabled={!readyTestResultData} onClick={() => startRun()}>
                                    <Icon icon="vaadin:bolt" slot={'prefix'} style={{ height: 'var(--lumo-icon-size-l)', width: 'var(--lumo-icon-size-l)' }} />
                                    Run test
                                </Button>,
                            }
                        }
                    }}
                />
            </CurrentUserGate>
            <LiveTestResult testResult={testResultData} reset={() => setTestResultData(undefined)}/>
        </VerticalLayout>
    );
}
