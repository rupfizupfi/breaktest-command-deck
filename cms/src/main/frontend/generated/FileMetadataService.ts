import { EndpointRequestInit as EndpointRequestInit_1 } from "@vaadin/hilla-frontend";
import type FileMetadata_1 from "./ch/rupfizupfi/deck/data/FileMetadata.js";
import type Filter_1 from "./com/vaadin/hilla/crud/filter/Filter.js";
import type Pageable_1 from "./com/vaadin/hilla/mappedtypes/Pageable.js";
import client_1 from "./connect-client.default.js";
async function count_1(filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<number> { return client_1.call("FileMetadataService", "count", { filter }, init); }
async function exists_1(id: number, init?: EndpointRequestInit_1): Promise<boolean> { return client_1.call("FileMetadataService", "exists", { id }, init); }
async function get_1(id: number, init?: EndpointRequestInit_1): Promise<FileMetadata_1 | undefined> { return client_1.call("FileMetadataService", "get", { id }, init); }
async function list_1(pageable: Pageable_1, filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<Array<FileMetadata_1>> { return client_1.call("FileMetadataService", "list", { pageable, filter }, init); }
async function delete_1(id: number, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("FileMetadataService", "delete", { id }, init); }
async function deleteAll_1(ids: Array<number>, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("FileMetadataService", "deleteAll", { ids }, init); }
async function save_1(value: FileMetadata_1, init?: EndpointRequestInit_1): Promise<FileMetadata_1 | undefined> { return client_1.call("FileMetadataService", "save", { value }, init); }
async function saveAll_1(values: Array<FileMetadata_1>, init?: EndpointRequestInit_1): Promise<Array<FileMetadata_1>> { return client_1.call("FileMetadataService", "saveAll", { values }, init); }
async function connectToTestResult_1(fileMetadata: FileMetadata_1, testResultId: number, init?: EndpointRequestInit_1): Promise<boolean> { return client_1.call("FileMetadataService", "connectToTestResult", { fileMetadata, testResultId }, init); }
export { connectToTestResult_1 as connectToTestResult, count_1 as count, delete_1 as delete, deleteAll_1 as deleteAll, exists_1 as exists, get_1 as get, list_1 as list, save_1 as save, saveAll_1 as saveAll };
