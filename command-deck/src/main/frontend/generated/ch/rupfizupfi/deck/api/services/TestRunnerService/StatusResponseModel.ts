import { _getPropertyModel as _getPropertyModel_1, BooleanModel as BooleanModel_1, makeObjectEmptyValueCreator as makeObjectEmptyValueCreator_1, ObjectModel as ObjectModel_1 } from "@vaadin/hilla-lit-form";
import TestResultModel_1 from "../../../data/TestResultModel.js";
import type StatusResponse_1 from "./StatusResponse.js";
class StatusResponseModel<T extends StatusResponse_1 = StatusResponse_1> extends ObjectModel_1<T> {
    static override createEmptyValue = makeObjectEmptyValueCreator_1(StatusResponseModel);
    get isRunning(): BooleanModel_1 {
        return this[_getPropertyModel_1]("isRunning", (parent, key) => new BooleanModel_1(parent, key, false, { meta: { javaType: "boolean" } }));
    }
    get testResult(): TestResultModel_1 {
        return this[_getPropertyModel_1]("testResult", (parent, key) => new TestResultModel_1(parent, key, true));
    }
}
export default StatusResponseModel;
