import { EndpointRequestInit as EndpointRequestInit_1 } from "@vaadin/hilla-frontend";
import type TestResult_1 from "./ch/rupfizupfi/deck/data/TestResult.js";
import type Filter_1 from "./com/vaadin/hilla/crud/filter/Filter.js";
import type Pageable_1 from "./com/vaadin/hilla/mappedtypes/Pageable.js";
import client_1 from "./connect-client.default.js";
async function count_1(filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<number> { return client_1.call("TestResultService", "count", { filter }, init); }
async function exists_1(id: number, init?: EndpointRequestInit_1): Promise<boolean> { return client_1.call("TestResultService", "exists", { id }, init); }
async function get_1(id: number, init?: EndpointRequestInit_1): Promise<TestResult_1 | undefined> { return client_1.call("TestResultService", "get", { id }, init); }
async function list_1(pageable: Pageable_1, filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<Array<TestResult_1>> { return client_1.call("TestResultService", "list", { pageable, filter }, init); }
async function delete_1(id: number, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("TestResultService", "delete", { id }, init); }
async function deleteAll_1(ids: Array<number>, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("TestResultService", "deleteAll", { ids }, init); }
async function save_1(value: TestResult_1, init?: EndpointRequestInit_1): Promise<TestResult_1 | undefined> { return client_1.call("TestResultService", "save", { value }, init); }
async function saveAll_1(values: Array<TestResult_1>, init?: EndpointRequestInit_1): Promise<Array<TestResult_1>> { return client_1.call("TestResultService", "saveAll", { values }, init); }
async function listCSVResults_1(id: number, init?: EndpointRequestInit_1): Promise<Array<string>> { return client_1.call("TestResultService", "listCSVResults", { id }, init); }
async function readCSVData_1(id: number, fileName: string, init?: EndpointRequestInit_1): Promise<string> { return client_1.call("TestResultService", "readCSVData", { id, fileName }, init); }
export { count_1 as count, delete_1 as delete, deleteAll_1 as deleteAll, exists_1 as exists, get_1 as get, list_1 as list, listCSVResults_1 as listCSVResults, readCSVData_1 as readCSVData, save_1 as save, saveAll_1 as saveAll };
