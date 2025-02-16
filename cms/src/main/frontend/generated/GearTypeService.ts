import { EndpointRequestInit as EndpointRequestInit_1 } from "@vaadin/hilla-frontend";
import type GearType_1 from "./ch/rupfizupfi/deck/data/GearType.js";
import type Filter_1 from "./com/vaadin/hilla/crud/filter/Filter.js";
import type Pageable_1 from "./com/vaadin/hilla/mappedtypes/Pageable.js";
import client_1 from "./connect-client.default.js";
async function count_1(filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<number> { return client_1.call("GearTypeService", "count", { filter }, init); }
async function exists_1(id: number, init?: EndpointRequestInit_1): Promise<boolean> { return client_1.call("GearTypeService", "exists", { id }, init); }
async function get_1(id: number, init?: EndpointRequestInit_1): Promise<GearType_1 | undefined> { return client_1.call("GearTypeService", "get", { id }, init); }
async function list_1(pageable: Pageable_1, filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<Array<GearType_1>> { return client_1.call("GearTypeService", "list", { pageable, filter }, init); }
async function delete_1(id: number, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("GearTypeService", "delete", { id }, init); }
async function deleteAll_1(ids: Array<number>, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("GearTypeService", "deleteAll", { ids }, init); }
async function save_1(value: GearType_1, init?: EndpointRequestInit_1): Promise<GearType_1 | undefined> { return client_1.call("GearTypeService", "save", { value }, init); }
async function saveAll_1(values: Array<GearType_1>, init?: EndpointRequestInit_1): Promise<Array<GearType_1>> { return client_1.call("GearTypeService", "saveAll", { values }, init); }
export { count_1 as count, delete_1 as delete, deleteAll_1 as deleteAll, exists_1 as exists, get_1 as get, list_1 as list, save_1 as save, saveAll_1 as saveAll };
