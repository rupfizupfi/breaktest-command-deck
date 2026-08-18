import type AbstractEntity_1 from "./AbstractEntity.js";
interface Customer extends AbstractEntity_1 {
    organization: string;
    firstname: string;
    lastname: string;
    email: string;
    street: string;
    code: string;
    location: string;
    country: string;
    label: string;
}
export default Customer;
