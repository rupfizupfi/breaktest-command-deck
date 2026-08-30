import { ConnectClient, type Middleware, type MiddlewareContext, type MiddlewareNext } from '@vaadin/hilla-frontend';
import { Notification, type NotificationElement } from '@vaadin/react-components/Notification.js';

/**
 * The single error policy for every generated Hilla RPC call, installed as a ConnectClient
 * middleware by both modules' `connect-client.ts`.
 *
 * Why a middleware, where it sits in the chain, and what it does per status:
 * `doc/04-frontend/hilla-generated-layer.md#the-hand-written-client`.
 */

/** Byte-identical to the generated default this replaces — no leading slash, or every RPC 404s. */
const PREFIX = 'connect';

const LOGIN_PATH = '/login';

/** Exempt from session recovery — it is the call `AuthProvider` makes on mount. */
const AUTH_ENDPOINT = 'UserEndpoint';

const TRANSIENT_DURATION_MS = 6000;
const REDIRECT_DELAY_MS = 1500;

/** Suppress repeats of the same (status, endpoint, method) while the last toast is still up. */
const DEDUPE_WINDOW_MS = 5000;

/** Survives the reload session recovery triggers; without it a 401 can loop the browser. */
const RELOAD_MARKER = 'deck.rpcErrorPolicy.sessionExpired';
const RELOAD_MARKER_TTL_MS = 30_000;

function reloadAlreadyAttempted(): boolean {
  try {
    const at = Number(sessionStorage.getItem(RELOAD_MARKER));
    return at > 0 && Date.now() - at < RELOAD_MARKER_TTL_MS;
  } catch {
    return false;
  }
}

function markReloadAttempt(): void {
  try {
    sessionStorage.setItem(RELOAD_MARKER, String(Date.now()));
  } catch {
    // sessionStorage can be unavailable (locked-down browser profile); the marker is a
    // loop guard, not correctness.
  }
}

function clearReloadAttempt(): void {
  try {
    sessionStorage.removeItem(RELOAD_MARKER);
  } catch {
    // see markReloadAttempt
  }
}

const shown = new Map<string, { element: NotificationElement; at: number }>();

/**
 * `Notification.show()` moves the visible card into a `vaadin-notification-container` in
 * `<body>`, so a click on the toast never reaches the element `show()` returned. Wire the card
 * once Lit has rendered it — a `duration: 0` toast otherwise has no way out. If the internal
 * accessor ever goes away the toast simply stays put.
 */
function makeDismissable(element: NotificationElement): void {
  requestAnimationFrame(() => {
    const card = (element as unknown as { _card?: HTMLElement })._card;
    if (!card) {
      return;
    }
    card.style.cursor = 'pointer';
    card.addEventListener('click', () => {
      element.opened = false;
    });
  });
}

function notify(key: string, message: string, persistent: boolean): void {
  const previous = shown.get(key);
  if (previous && (previous.element.opened || Date.now() - previous.at < DEDUPE_WINDOW_MS)) {
    return;
  }

  const element = Notification.show(message, {
    duration: persistent ? 0 : TRANSIENT_DURATION_MS,
    theme: 'error',
  });
  if (persistent) {
    makeDismissable(element);
  }
  shown.set(key, { element, at: Date.now() });
}

let recovering = false;

function recoverFromLostSession(): void {
  if (recovering || window.location.pathname === LOGIN_PATH) {
    return;
  }
  recovering = true;

  Notification.show('Your session has ended. Returning you to the sign-in screen…', {
    duration: REDIRECT_DELAY_MS,
    position: 'middle',
    theme: 'error',
  });

  // Full-page navigation, not react-router: `configureAuth` computes its state once at
  // AuthProvider mount and `useAuth` exposes no refresh, so only a document load re-initialises
  // it — the same move `views/@layout.tsx` makes after logout. The target is the current path,
  // not /login; a `?redirectUrl=` query parameter would be ignored, because login.tsx reads
  // redirectUrl from the login *response*.
  const target = reloadAlreadyAttempted() ? LOGIN_PATH : window.location.pathname + window.location.search;
  markReloadAttempt();
  window.setTimeout(() => window.location.assign(target), REDIRECT_DELAY_MS);
}

type EndpointErrorBody = Readonly<{
  message?: string;
  type?: string;
  validationErrorData?: readonly unknown[];
}>;

/** Clone first, always: `assertResponseIsOk` awaits `response.text()` one level up the chain. */
async function readErrorBody(response: Response): Promise<EndpointErrorBody | undefined> {
  try {
    const text = await response.clone().text();
    return text ? (JSON.parse(text) as EndpointErrorBody) : undefined;
  } catch {
    return undefined;
  }
}

/** `status === 0` means fetch itself rejected: there is no Response to inspect. */
function messageFor(status: number, endpoint: string, method: string, serverMessage?: string): string {
  const where = `${endpoint}.${method}`;
  if (status === 0) {
    return `Cannot reach the server (${where}). Check the connection and try again.`;
  }
  if (status === 403) {
    return serverMessage ?? `Not permitted: ${where}.`;
  }
  return serverMessage ?? `The server could not complete ${where} (HTTP ${status}).`;
}

function report(context: MiddlewareContext, status: number, serverMessage?: string): void {
  const { endpoint, method } = context;
  // Never log context.params — UserService.save carries newPassword.
  console.error(`Hilla RPC ${endpoint}.${method} failed`, { status, message: serverMessage });

  if (recovering) {
    // A redirect is already in flight; the toasts from calls dying alongside it are noise.
    return;
  }

  // 403 is persistent (click to dismiss): nothing will retry it.
  notify(`${status}:${endpoint}:${method}`, messageFor(status, endpoint, method, serverMessage), status === 403);
}

let reloadMarkerCleared = false;

export const rpcErrorPolicy: Middleware = async (
  context: MiddlewareContext,
  next: MiddlewareNext,
): Promise<Response> => {
  let response: Response;
  try {
    response = await next(context);
  } catch (error) {
    report(context, 0, error instanceof Error ? error.message : undefined);
    throw error;
  }

  if (response.ok) {
    // UserEndpoint answers 200 even when logged out, so it must not be what clears the guard.
    if (!reloadMarkerCleared && context.endpoint !== AUTH_ENDPOINT) {
      reloadMarkerCleared = true;
      clearReloadAttempt();
    }
    return response;
  }

  const body = await readErrorBody(response);

  // Probed before the status checks, matching the discrimination order in
  // Connect.js#assertResponseIsOk. autoform.js already renders these into the form.
  if (body?.validationErrorData) {
    return response;
  }

  if (response.status === 401 && context.endpoint !== AUTH_ENDPOINT) {
    recoverFromLostSession();
    return response;
  }

  report(context, response.status, body?.message);
  return response;
};

/** Builds the ConnectClient both modules export as their default. */
export function createConnectClient(): ConnectClient {
  return new ConnectClient({ prefix: PREFIX, middlewares: [rpcErrorPolicy] });
}
