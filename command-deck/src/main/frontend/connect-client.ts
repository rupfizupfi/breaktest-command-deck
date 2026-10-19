/**
 * Custom ConnectClient for this module's generated Hilla services. Its twin is
 * `cms/src/main/frontend/connect-client.ts`; without it a module falls back to the generated default
 * and loses the error policy, which lives in cms. See
 * `doc/dev/frontend/hilla-generated-layer.md#the-hand-written-client`.
 */
import { createConnectClient } from 'cms/util/rpcErrorPolicy.js';

export default createConnectClient();
