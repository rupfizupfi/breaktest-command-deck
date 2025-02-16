import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {SettingService} from 'Frontend/generated/endpoints.js';
import {Button, TextField, VerticalLayout} from "@vaadin/react-components";
import SettingModel from "Frontend/generated/ch/rupfizupfi/deck/data/SettingModel";
import React, {useState} from "react";
import {AutoCrud} from "Frontend/components/autocrud/AutoCrud";
import {getDynamicField} from "Frontend/components/control/dynamicField";
import Setting from "Frontend/generated/ch/rupfizupfi/deck/data/Setting";
import {EndpointRequestInit} from "@vaadin/hilla-frontend/Connect.js";

export const config: ViewConfig = {
    menu: {order: 1, icon: 'line-awesome/svg/suitcase-rolling-solid.svg', exclude: true},
    title: 'Setting',
    loginRequired: true,
};

const customSettingService = {
    ...SettingService, save: (item: Setting, init?: EndpointRequestInit) => {
        const valueType = typeof item.value;
        switch (item.type) {
            case 'java.lang.Number':
            case 'java.lang.Integer':
            case 'java.lang.Long':
                if (valueType === 'string') {
                    item.value = Number(item.value);
                }
                break;
            case 'java.lang.Boolean':
                if (valueType === 'string') {
                    item.value = item.value === 'on' || item.value === 'true';
                }
                break;
        }

        return SettingService.save(item, init);
    }
};

export default function SettingView() {
    const [reload, setReload] = useState(Date.now());

    return (
        <VerticalLayout theme="padding spacing-l stretch evenly">
            <h1>Setting {reload}</h1>
            <div className="w-full">
                <AutoCrud
                    service={customSettingService}
                    model={SettingModel}
                    formProps={{
                        visibleFields: ['key', 'value'],
                        fieldOptions: {
                            value: {renderer: ({field}) => <TextField {...field} />}
                        },
                        disableAddButtons: true
                    }}
                    formFieldUpdater={(item, formProps) => {
                        formProps.fieldOptions.value.renderer = getDynamicField(item.value, item.type);
                    }}
                />
            </div>
            <div className="w-full">
                <Button theme="secondary" onClick={() => customSettingService.sync().then(()=> setReload(Date.now()))}>Sync</Button>
            </div>
        </VerticalLayout>
    )
}
