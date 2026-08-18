import { _enum as _enum_1, EnumModel as EnumModel_1, makeEnumEmptyValueCreator as makeEnumEmptyValueCreator_1 } from "@vaadin/hilla-lit-form";
import Role_1 from "./Role.js";
class RoleModel extends EnumModel_1<typeof Role_1> {
    static override createEmptyValue = makeEnumEmptyValueCreator_1(RoleModel);
    readonly [_enum_1] = Role_1;
}
export default RoleModel;
