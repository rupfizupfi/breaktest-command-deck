import { _getPropertyModel as _getPropertyModel_1, ArrayModel as ArrayModel_1, makeObjectEmptyValueCreator as makeObjectEmptyValueCreator_1, Max as Max_1, Min as Min_1, NumberModel as NumberModel_1, StringModel as StringModel_1 } from "@vaadin/hilla-lit-form";
import AbstractEntityModel_1 from "./AbstractEntityModel.js";
import GearStandardModel_1 from "./GearStandardModel.js";
import GearTypeModel_1 from "./GearTypeModel.js";
import MaterialModel_1 from "./MaterialModel.js";
import ProjectModel_1 from "./ProjectModel.js";
import type Sample_1 from "./Sample.js";
import UserModel_1 from "./UserModel.js";
class SampleModel<T extends Sample_1 = Sample_1> extends AbstractEntityModel_1<T> {
    static override createEmptyValue = makeObjectEmptyValueCreator_1(SampleModel);
    get id(): NumberModel_1 {
        return this[_getPropertyModel_1]("id", (parent, key) => new NumberModel_1(parent, key, true, { meta: { annotations: [{ name: "jakarta.persistence.Id" }], javaType: "java.lang.Long" } }));
    }
    get owner(): UserModel_1 {
        return this[_getPropertyModel_1]("owner", (parent, key) => new UserModel_1(parent, key, true, { meta: { annotations: [{ name: "jakarta.persistence.ManyToOne" }] } }));
    }
    get project(): ProjectModel_1 {
        return this[_getPropertyModel_1]("project", (parent, key) => new ProjectModel_1(parent, key, false, { meta: { annotations: [{ name: "jakarta.persistence.ManyToOne" }] } }));
    }
    get name(): StringModel_1 {
        return this[_getPropertyModel_1]("name", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get description(): StringModel_1 {
        return this[_getPropertyModel_1]("description", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get model(): StringModel_1 {
        return this[_getPropertyModel_1]("model", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get manufacturer(): StringModel_1 {
        return this[_getPropertyModel_1]("manufacturer", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get yearOfManufacture(): NumberModel_1 {
        return this[_getPropertyModel_1]("yearOfManufacture", (parent, key) => new NumberModel_1(parent, key, false, { validators: [new Min_1({ message: "Year of manufacture must be between 1900 and 2900", value: 1900 }), new Max_1({ message: "Year of manufacture must be between 1900 and 2900", value: 2900 })], meta: { javaType: "int" } }));
    }
    get gearTypes(): ArrayModel_1<GearTypeModel_1> {
        return this[_getPropertyModel_1]("gearTypes", (parent, key) => new ArrayModel_1(parent, key, false, (parent, key) => new GearTypeModel_1(parent, key, false), { meta: { annotations: [{ name: "jakarta.persistence.ManyToMany" }], javaType: "java.util.List" } }));
    }
    get gearStandards(): ArrayModel_1<GearStandardModel_1> {
        return this[_getPropertyModel_1]("gearStandards", (parent, key) => new ArrayModel_1(parent, key, false, (parent, key) => new GearStandardModel_1(parent, key, false), { meta: { annotations: [{ name: "jakarta.persistence.ManyToMany" }], javaType: "java.util.List" } }));
    }
    get materials(): ArrayModel_1<MaterialModel_1> {
        return this[_getPropertyModel_1]("materials", (parent, key) => new ArrayModel_1(parent, key, false, (parent, key) => new MaterialModel_1(parent, key, false), { meta: { annotations: [{ name: "jakarta.persistence.ManyToMany" }], javaType: "java.util.List" } }));
    }
}
export default SampleModel;
