import { _getPropertyModel as _getPropertyModel_1, makeObjectEmptyValueCreator as makeObjectEmptyValueCreator_1, NotBlank as NotBlank_1, StringModel as StringModel_1 } from "@vaadin/hilla-lit-form";
import AbstractEntityModel_1 from "./AbstractEntityModel.js";
import CustomerModel_1 from "./CustomerModel.js";
import type Project_1 from "./Project.js";
import UserModel_1 from "./UserModel.js";
class ProjectModel<T extends Project_1 = Project_1> extends AbstractEntityModel_1<T> {
    static override createEmptyValue = makeObjectEmptyValueCreator_1(ProjectModel);
    get owner(): UserModel_1 {
        return this[_getPropertyModel_1]("owner", (parent, key) => new UserModel_1(parent, key, true, { meta: { annotations: [{ name: "jakarta.persistence.ManyToOne" }] } }));
    }
    get name(): StringModel_1 {
        return this[_getPropertyModel_1]("name", (parent, key) => new StringModel_1(parent, key, false, { validators: [new NotBlank_1()], meta: { javaType: "java.lang.String" } }));
    }
    get customer(): CustomerModel_1 {
        return this[_getPropertyModel_1]("customer", (parent, key) => new CustomerModel_1(parent, key, false, { meta: { annotations: [{ name: "jakarta.persistence.ManyToOne" }] } }));
    }
    get description(): StringModel_1 {
        return this[_getPropertyModel_1]("description", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
}
export default ProjectModel;
