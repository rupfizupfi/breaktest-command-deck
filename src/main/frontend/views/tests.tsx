import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {AutoCrud} from "@vaadin/hilla-react-crud";
import {ProjectService} from "Frontend/generated/endpoints";
import ProjectModel from "Frontend/generated/ch/rupfizupfi/deck/data/ProjectModel";

export const config: ViewConfig = {menu: {order: 1, icon: 'line-awesome/svg/file.svg'}, title: 'Tests', loginRequired: true};

export default function TestsView() {
    return (
        <div>
            <div className="flex flex-col h-full items-center justify-center p-l text-center box-border">
                <img style={{width: '200px'}} src="images/empty-plant.png"/>
                <h2>This place intentionally left empty</h2>
                <p>It’s a place where you can grow your own UI 🤗</p>
            </div>
        </div>
    );
}
