import { EndpointRequestInit as EndpointRequestInit_1 } from "@vaadin/hilla-frontend";
import type Setting_1 from "./ch/rupfizupfi/deck/data/Setting.js";
import type Filter_1 from "./com/vaadin/hilla/crud/filter/Filter.js";
import type Pageable_1 from "./com/vaadin/hilla/mappedtypes/Pageable.js";
import client_1 from "./connect-client.default.js";
async function delete_1(s: string, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("SettingService", "delete", { s }, init); }
async function getSetting_1(key: string, init?: EndpointRequestInit_1): Promise<Setting_1<unknown>> { return client_1.call("SettingService", "getSetting", { key }, init); }
async function list_1(pageable: Pageable_1, filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<Array<Setting_1<unknown>>> { return client_1.call("SettingService", "list", { pageable, filter }, init); }
async function save_1(value: Setting_1<unknown>, init?: EndpointRequestInit_1): Promise<Setting_1<unknown> | undefined> { return client_1.call("SettingService", "save", { value }, init); }
async function sync_1(init?: EndpointRequestInit_1): Promise<Array<Setting_1<unknown>>> { return client_1.call("SettingService", "sync", {}, init); }
export { delete_1 as delete, getSetting_1 as getSetting, list_1 as list, save_1 as save, sync_1 as sync };
