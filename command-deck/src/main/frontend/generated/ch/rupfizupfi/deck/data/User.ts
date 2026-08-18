import type AbstractEntity_1 from "./AbstractEntity.js";
import type Role_1 from "./Role.js";
interface User extends AbstractEntity_1 {
    id?: number;
    username: string;
    name: string;
    newPassword?: string;
    roles: Array<Role_1>;
}
export default User;
