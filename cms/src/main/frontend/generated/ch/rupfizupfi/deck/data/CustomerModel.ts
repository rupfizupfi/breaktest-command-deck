import { _getPropertyModel as _getPropertyModel_1, Email as Email_1, makeObjectEmptyValueCreator as makeObjectEmptyValueCreator_1, Pattern as Pattern_1, StringModel as StringModel_1 } from "@vaadin/hilla-lit-form";
import AbstractEntityModel_1 from "./AbstractEntityModel.js";
import type Customer_1 from "./Customer.js";
class CustomerModel<T extends Customer_1 = Customer_1> extends AbstractEntityModel_1<T> {
    static override createEmptyValue = makeObjectEmptyValueCreator_1(CustomerModel);
    get organization(): StringModel_1 {
        return this[_getPropertyModel_1]("organization", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get firstname(): StringModel_1 {
        return this[_getPropertyModel_1]("firstname", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get lastname(): StringModel_1 {
        return this[_getPropertyModel_1]("lastname", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get email(): StringModel_1 {
        return this[_getPropertyModel_1]("email", (parent, key) => new StringModel_1(parent, key, false, { validators: [new Email_1()], meta: { javaType: "java.lang.String" } }));
    }
    get street(): StringModel_1 {
        return this[_getPropertyModel_1]("street", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get code(): StringModel_1 {
        return this[_getPropertyModel_1]("code", (parent, key) => new StringModel_1(parent, key, false, { validators: [new Pattern_1({ regexp: "^\\d{4,5}$", message: "Invalid Postal code" })], meta: { javaType: "java.lang.String" } }));
    }
    get location(): StringModel_1 {
        return this[_getPropertyModel_1]("location", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get country(): StringModel_1 {
        return this[_getPropertyModel_1]("country", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get label(): StringModel_1 {
        return this[_getPropertyModel_1]("label", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
}
export default CustomerModel;
