import { EndpointRequestInit as EndpointRequestInit_1 } from "@vaadin/hilla-frontend";
import client_1 from "./connect-client.default.js";
async function disable_1(init?: EndpointRequestInit_1): Promise<boolean> { return client_1.call("SuckService", "disable", {}, init); }
async function enable_1(init?: EndpointRequestInit_1): Promise<boolean> { return client_1.call("SuckService", "enable", {}, init); }
export { disable_1 as disable, enable_1 as enable };
