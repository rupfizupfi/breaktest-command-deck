import { _getPropertyModel as _getPropertyModel_1, makeObjectEmptyValueCreator as makeObjectEmptyValueCreator_1, ObjectModel as ObjectModel_1, StringModel as StringModel_1 } from "@vaadin/hilla-lit-form";
import type Setting_1 from "./Setting.js";
class SettingModel<T extends Setting_1 = Setting_1> extends ObjectModel_1<T> {
    static override createEmptyValue = makeObjectEmptyValueCreator_1(SettingModel);
    get key(): StringModel_1 {
        return this[_getPropertyModel_1]("key", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get value(): ObjectModel_1 {
        return this[_getPropertyModel_1]("value", (parent, key) => new ObjectModel_1(parent, key, false));
    }
    get type(): StringModel_1 {
        return this[_getPropertyModel_1]("type", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
}
export default SettingModel;
