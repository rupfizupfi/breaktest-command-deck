import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {TestParameterService} from "Frontend/generated/endpoints";
import {buildAutoCrud} from "cms/components/autocrud/test";
import {constraintServiceToFilter} from "cms/util/service";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";
import TestParameterModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameterModel";
import TestType from "Frontend/generated/ch/rupfizupfi/deck/data/TestType";

export const config: ViewConfig = {menu: {order: 1, icon: 'line-awesome/svg/test-tube.svg', exclude: true}, title: 'Cyclic Test', loginRequired: true};

class DestructionTestParameterModel extends TestParameterModel {
    static override createEmptyValue() {
        return {
            ...TestParameterModel.createEmptyValue(),
            type: TestType.CYCLIC,
        };
    }
}

export default function DestructiveTestView() {
    const localTestParameterService = constraintServiceToFilter(TestParameterService, {
        propertyId: 'type',
        filterValue: TestType.CYCLIC,
        matcher: Matcher.EQUALS,
        '@type': 'propertyString',
    });

    return buildAutoCrud(localTestParameterService, DestructionTestParameterModel, ['upperTurnForce', 'lowerTurnForce', 'upperShutOffThreshold', 'cycleCount']);
}
