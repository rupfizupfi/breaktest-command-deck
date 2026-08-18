import { EndpointRequestInit as EndpointRequestInit_1 } from "@vaadin/hilla-frontend";
import type StatusResponse_1 from "./ch/rupfizupfi/deck/api/services/TestRunnerService/StatusResponse.js";
import client_1 from "./connect-client.default.js";
async function start_1(testId: number, init?: EndpointRequestInit_1): Promise<void> { return client_1.call("TestRunnerService", "start", { testId }, init); }
async function status_1(init?: EndpointRequestInit_1): Promise<StatusResponse_1> { return client_1.call("TestRunnerService", "status", {}, init); }
async function stop_1(init?: EndpointRequestInit_1): Promise<void> { return client_1.call("TestRunnerService", "stop", {}, init); }
export { start_1 as start, status_1 as status, stop_1 as stop };
