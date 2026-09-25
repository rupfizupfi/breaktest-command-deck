/**
 * Custom ConnectClient for this module's generated Hilla services. Its twin is
 * `cms/src/main/frontend/connect-client.ts` — both must exist, or the module without one falls
 * back to the generated default and silently loses the error policy. The policy lives once, in cms.
 *
 * Why this file replaces `generated/connect-client.default.ts` at all, and the constraints it
 * has to satisfy: `doc/04-frontend/hilla-generated-layer.md#the-hand-written-client`.
 */
import { createConnectClient } from 'cms/util/rpcErrorPolicy.js';

export default createConnectClient();
