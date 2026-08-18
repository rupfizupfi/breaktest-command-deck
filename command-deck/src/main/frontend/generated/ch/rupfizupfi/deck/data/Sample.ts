import type AbstractEntity_1 from "./AbstractEntity.js";
import type GearStandard_1 from "./GearStandard.js";
import type GearType_1 from "./GearType.js";
import type Material_1 from "./Material.js";
import type Project_1 from "./Project.js";
import type User_1 from "./User.js";
interface Sample extends AbstractEntity_1 {
    id?: number;
    owner?: User_1;
    project: Project_1;
    name: string;
    description: string;
    model: string;
    manufacturer: string;
    yearOfManufacture: number;
    gearTypes: Array<GearType_1>;
    gearStandards: Array<GearStandard_1>;
    materials: Array<Material_1>;
}
export default Sample;
