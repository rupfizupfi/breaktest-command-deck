import { _getPropertyModel as _getPropertyModel_1, ArrayModel as ArrayModel_1, BooleanModel as BooleanModel_1, makeObjectEmptyValueCreator as makeObjectEmptyValueCreator_1, StringModel as StringModel_1 } from "@vaadin/hilla-lit-form";
import AbstractEntityModel_1 from "./AbstractEntityModel.js";
import FileMetadataModel_1 from "./FileMetadataModel.js";
import SampleModel_1 from "./SampleModel.js";
import TestParameterModel_1 from "./TestParameterModel.js";
import type TestResult_1 from "./TestResult.js";
import UserModel_1 from "./UserModel.js";
class TestResultModel<T extends TestResult_1 = TestResult_1> extends AbstractEntityModel_1<T> {
    static override createEmptyValue = makeObjectEmptyValueCreator_1(TestResultModel);
    get owner(): UserModel_1 {
        return this[_getPropertyModel_1]("owner", (parent, key) => new UserModel_1(parent, key, true, { meta: { annotations: [{ name: "jakarta.persistence.ManyToOne" }] } }));
    }
    get sample(): SampleModel_1 {
        return this[_getPropertyModel_1]("sample", (parent, key) => new SampleModel_1(parent, key, false, { meta: { annotations: [{ name: "jakarta.persistence.ManyToOne" }] } }));
    }
    get testParameter(): TestParameterModel_1 {
        return this[_getPropertyModel_1]("testParameter", (parent, key) => new TestParameterModel_1(parent, key, false, { meta: { annotations: [{ name: "jakarta.persistence.ManyToOne" }] } }));
    }
    get description(): StringModel_1 {
        return this[_getPropertyModel_1]("description", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get resultText(): StringModel_1 {
        return this[_getPropertyModel_1]("resultText", (parent, key) => new StringModel_1(parent, key, true, { meta: { javaType: "java.lang.String" } }));
    }
    get files(): ArrayModel_1<FileMetadataModel_1> {
        return this[_getPropertyModel_1]("files", (parent, key) => new ArrayModel_1(parent, key, false, (parent, key) => new FileMetadataModel_1(parent, key, false), { meta: { annotations: [{ name: "jakarta.persistence.OneToMany" }], javaType: "java.util.List" } }));
    }
    get run(): BooleanModel_1 {
        return this[_getPropertyModel_1]("run", (parent, key) => new BooleanModel_1(parent, key, false, { meta: { javaType: "boolean" } }));
    }
}
export default TestResultModel;
