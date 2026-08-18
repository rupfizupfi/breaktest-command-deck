import type AbstractEntity_1 from "./AbstractEntity.js";
import type FileMetadata_1 from "./FileMetadata.js";
import type Sample_1 from "./Sample.js";
import type TestParameter_1 from "./TestParameter.js";
import type User_1 from "./User.js";
interface TestResult extends AbstractEntity_1 {
    owner?: User_1;
    sample: Sample_1;
    testParameter: TestParameter_1;
    description: string;
    resultText?: string;
    files: Array<FileMetadata_1>;
    run: boolean;
}
export default TestResult;
