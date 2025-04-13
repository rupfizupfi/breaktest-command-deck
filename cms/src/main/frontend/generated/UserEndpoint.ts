import { EndpointRequestInit as EndpointRequestInit_1 } from "@vaadin/hilla-frontend";
import type User_1 from "./ch/rupfizupfi/deck/data/User.js";
import type Filter_1 from "./com/vaadin/hilla/crud/filter/Filter.js";
import type Pageable_1 from "./com/vaadin/hilla/mappedtypes/Pageable.js";
import client_1 from "./connect-client.default.js";
async function getAuthenticatedUser_1(init?: EndpointRequestInit_1): Promise<User_1 | undefined> { return client_1.call("UserEndpoint", "getAuthenticatedUser", {}, init); }
async function list_1(pageable: Pageable_1 | undefined, filter: Filter_1 | undefined, init?: EndpointRequestInit_1): Promise<Array<User_1 | undefined>> { return client_1.call("UserEndpoint", "list", { pageable, filter }, init); }
export { getAuthenticatedUser_1 as getAuthenticatedUser, list_1 as list };
