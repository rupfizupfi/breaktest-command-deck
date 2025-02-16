import { _getPropertyModel as _getPropertyModel_1, makeObjectEmptyValueCreator as makeObjectEmptyValueCreator_1, NumberModel as NumberModel_1, StringModel as StringModel_1 } from "@vaadin/hilla-lit-form";
import AbstractEntityModel_1 from "./AbstractEntityModel.js";
import type TestParameter_1 from "./TestParameter.js";
import UserModel_1 from "./UserModel.js";
class TestParameterModel<T extends TestParameter_1 = TestParameter_1> extends AbstractEntityModel_1<T> {
    static override createEmptyValue = makeObjectEmptyValueCreator_1(TestParameterModel);
    get id(): NumberModel_1 {
        return this[_getPropertyModel_1]("id", (parent, key) => new NumberModel_1(parent, key, true, { meta: { annotations: [{ name: "jakarta.persistence.Id" }], javaType: "java.lang.Long" } }));
    }
    get owner(): UserModel_1 {
        return this[_getPropertyModel_1]("owner", (parent, key) => new UserModel_1(parent, key, true, { meta: { annotations: [{ name: "jakarta.persistence.ManyToOne" }] } }));
    }
    get type(): StringModel_1 {
        return this[_getPropertyModel_1]("type", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get speed(): NumberModel_1 {
        return this[_getPropertyModel_1]("speed", (parent, key) => new NumberModel_1(parent, key, false, { meta: { javaType: "int" } }));
    }
    get upperShutOffThreshold(): NumberModel_1 {
        return this[_getPropertyModel_1]("upperShutOffThreshold", (parent, key) => new NumberModel_1(parent, key, true, { meta: { javaType: "java.lang.Double" } }));
    }
    get lowerShutOffThreshold(): NumberModel_1 {
        return this[_getPropertyModel_1]("lowerShutOffThreshold", (parent, key) => new NumberModel_1(parent, key, true, { meta: { javaType: "java.lang.Double" } }));
    }
    get upperTurnForce(): NumberModel_1 {
        return this[_getPropertyModel_1]("upperTurnForce", (parent, key) => new NumberModel_1(parent, key, true, { meta: { javaType: "java.lang.Double" } }));
    }
    get lowerTurnForce(): NumberModel_1 {
        return this[_getPropertyModel_1]("lowerTurnForce", (parent, key) => new NumberModel_1(parent, key, true, { meta: { javaType: "java.lang.Double" } }));
    }
    get cycleCount(): NumberModel_1 {
        return this[_getPropertyModel_1]("cycleCount", (parent, key) => new NumberModel_1(parent, key, true, { meta: { javaType: "java.lang.Integer" } }));
    }
    get startRampSeconds(): NumberModel_1 {
        return this[_getPropertyModel_1]("startRampSeconds", (parent, key) => new NumberModel_1(parent, key, true, { meta: { javaType: "java.lang.Double" } }));
    }
    get stopRampSeconds(): NumberModel_1 {
        return this[_getPropertyModel_1]("stopRampSeconds", (parent, key) => new NumberModel_1(parent, key, true, { meta: { javaType: "java.lang.Double" } }));
    }
    get label(): StringModel_1 {
        return this[_getPropertyModel_1]("label", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
}
export default TestParameterModel;
