import { EndpointRequestInit as EndpointRequestInit_1 } from "@vaadin/hilla-frontend";
import type GearStandard_1 from "./ch/rupfizupfi/deck/data/GearStandard.js";
import type Filter_1 from "./com/vaadin/hilla/crud/filter/Filter.js";
import type Pageable_1 from "./com/vaadin/hilla/mappedtypes/Pageable.js";
import client_1 from "./connect-client.default.js";
async function count_1(filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<number> { return client_1.call("GearStandardService", "count", { filter }, init); }
async function exists_1(id: number, init?: EndpointRequestInit_1): Promise<boolean> { return client_1.call("GearStandardService", "exists", { id }, init); }
async function get_1(id: number, init?: EndpointRequestInit_1): Promise<GearStandard_1 | undefined> { return client_1.call("GearStandardService", "get", { id }, init); }
async function list_1(pageable: Pageable_1, filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<Array<GearStandard_1>> { return client_1.call("GearStandardService", "list", { pageable, filter }, init); }
async function delete_1(id: number, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("GearStandardService", "delete", { id }, init); }
async function deleteAll_1(ids: Array<number>, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("GearStandardService", "deleteAll", { ids }, init); }
async function save_1(value: GearStandard_1, init?: EndpointRequestInit_1): Promise<GearStandard_1 | undefined> { return client_1.call("GearStandardService", "save", { value }, init); }
async function saveAll_1(values: Array<GearStandard_1>, init?: EndpointRequestInit_1): Promise<Array<GearStandard_1>> { return client_1.call("GearStandardService", "saveAll", { values }, init); }
export { count_1 as count, delete_1 as delete, deleteAll_1 as deleteAll, exists_1 as exists, get_1 as get, list_1 as list, save_1 as save, saveAll_1 as saveAll };
