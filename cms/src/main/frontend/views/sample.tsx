import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {SampleService} from "Frontend/generated/endpoints";
import {buildAutoCrud} from "Frontend/components/autocrud/sample";

export const config: ViewConfig = {menu: {order: 3, icon: 'line-awesome/svg/folder-open.svg'}, title: 'Sample', loginRequired: true};

export default function CustomerView() {
    return (
        buildAutoCrud(SampleService)
    );
}
