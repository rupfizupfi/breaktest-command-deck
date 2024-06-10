import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {TestParameterService} from "Frontend/generated/endpoints";
import {buildAutoCrud} from "Frontend/components/autocrud/test";
import {constraintServiceToFilter} from "Frontend/util/service";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";
import TestParameterModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameterModel";

export const config: ViewConfig = {menu: {order: 1, icon: 'line-awesome/svg/test-tube.svg', exclude: true}, title: 'Destructive Test', loginRequired: true};

class DestructionTestParameterModel extends TestParameterModel {
    static override createEmptyValue() {
        console.log(TestParameterModel.createEmptyValue());
        return {
            ...TestParameterModel.createEmptyValue(),
            type: 'destructive',
        };
    }
}

export default function DestructiveTestView() {
    const localTestParameterService = constraintServiceToFilter(TestParameterService, {
        propertyId: 'type',
        filterValue: 'destructive',
        matcher: Matcher.EQUALS,
        '@type': 'propertyString',
    });

    return buildAutoCrud(localTestParameterService, DestructionTestParameterModel);
}
