/**
 * ConnectClient for this module's Hilla services, twin of
 * `command-deck/src/main/frontend/connect-client.ts`; a module without one falls back to the
 * generated default and loses the error policy.
 * See `doc/dev/frontend/hilla-generated-layer.md#the-hand-written-client`.
 */
import { createConnectClient } from './util/rpcErrorPolicy.js';

export default createConnectClient();
