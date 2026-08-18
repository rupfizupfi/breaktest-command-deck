import type AbstractEntity_1 from "./AbstractEntity.js";
import type Customer_1 from "./Customer.js";
import type User_1 from "./User.js";
interface Project extends AbstractEntity_1 {
    owner?: User_1;
    name: string;
    customer: Customer_1;
    description: string;
}
export default Project;
