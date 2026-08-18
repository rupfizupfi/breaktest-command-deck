import type AbstractEntity_1 from "./AbstractEntity.js";
import type User_1 from "./User.js";
interface TestParameter extends AbstractEntity_1 {
    id?: number;
    owner?: User_1;
    type: string;
    speed: number;
    upperShutOffThreshold?: number;
    lowerShutOffThreshold?: number;
    upperTurnForce?: number;
    lowerTurnForce?: number;
    cycleCount?: number;
    startRampSeconds?: number;
    stopRampSeconds?: number;
    label: string;
}
export default TestParameter;
