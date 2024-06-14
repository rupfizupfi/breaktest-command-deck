import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {GearTypeService, GearStandardService, MaterialService} from 'Frontend/generated/endpoints.js';
import {VerticalLayout} from "@vaadin/react-components";
import {AutoCrud} from "@vaadin/hilla-react-crud";
import MaterialModel from "Frontend/generated/ch/rupfizupfi/deck/data/MaterialModel";
import GearTypeModel from "Frontend/generated/ch/rupfizupfi/deck/data/GearTypeModel";
import GearStandardModel from "Frontend/generated/ch/rupfizupfi/deck/data/GearStandardModel";

export const config: ViewConfig = {
    menu: {order: 1, icon: 'line-awesome/svg/suitcase-rolling-solid.svg'},
    title: 'System',
    loginRequired: true,
};

export default function SystemView() {
    return (
        <VerticalLayout theme="padding spacing-l stretch evenly">
            <h1>System</h1>
            <div className="w-full">
                <h2>Gear Type</h2>
                <AutoCrud service={GearTypeService} model={GearTypeModel} />
            </div>
            <div className="w-full">
                <h2 className="lumo-space-m">Gear Standard</h2>
                <AutoCrud service={GearStandardService} model={GearStandardModel}/>
            </div>
            <div className="w-full">
                <h2>Material</h2>
                <AutoCrud service={MaterialService} model={MaterialModel}/>
            </div>
        </VerticalLayout>
    )
}
