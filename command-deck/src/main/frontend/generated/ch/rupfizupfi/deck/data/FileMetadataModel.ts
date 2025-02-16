import { _getPropertyModel as _getPropertyModel_1, makeObjectEmptyValueCreator as makeObjectEmptyValueCreator_1, StringModel as StringModel_1 } from "@vaadin/hilla-lit-form";
import AbstractEntityModel_1 from "./AbstractEntityModel.js";
import type FileMetadata_1 from "./FileMetadata.js";
class FileMetadataModel<T extends FileMetadata_1 = FileMetadata_1> extends AbstractEntityModel_1<T> {
    static override createEmptyValue = makeObjectEmptyValueCreator_1(FileMetadataModel);
    get fileName(): StringModel_1 {
        return this[_getPropertyModel_1]("fileName", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
    get filePath(): StringModel_1 {
        return this[_getPropertyModel_1]("filePath", (parent, key) => new StringModel_1(parent, key, false, { meta: { javaType: "java.lang.String" } }));
    }
}
export default FileMetadataModel;
