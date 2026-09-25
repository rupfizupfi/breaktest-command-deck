/**
 * Pure-logic tests for the RPC error policy (node environment — no browser, no jsdom).
 *
 * The DOM boundary is stubbed at the two module seams the policy touches:
 * `@vaadin/react-components/Notification.js` (a shadow-DOM web component jsdom cannot
 * ConnectClient. What the toast *looks like* is out of scope here; what the policy
 * *decides* — status→message mapping, dedupe, 401 recovery, marker lifecycle — is the
 * subject. Browser globals the policy reads (sessionStorage, window.location,
 * window.setTimeout, requestAnimationFrame) are per-test stubs.
 *
 * The policy keeps module-level state (dedupe map, `recovering`, the marker-clear
 * latch), so every test re-imports it fresh via loadPolicy(); a second load inside one
 * test simulates the page reload that session recovery triggers.
 */
import { afterEach, beforeEach, describe, expect, it, vi, type Mock, type MockInstance } from 'vitest';
import type { MiddlewareContext, MiddlewareNext } from '@vaadin/hilla-frontend';

vi.mock('@vaadin/hilla-frontend', () => ({
  ConnectClient: class ConnectClient {
    constructor(readonly options: unknown) {}
  },
}));

vi.mock('@vaadin/react-components/Notification.js', () => ({
  Notification: {
    show: vi.fn(() => ({ opened: true })),
  },
}));

const RELOAD_MARKER = 'deck.rpcErrorPolicy.sessionExpired';
const SESSION_TOAST = 'Your session has ended. Returning you to the sign-in screen…';

class MemoryStorage {
  private readonly map = new Map<string, string>();

  getItem(key: string): string | null {
    return this.map.has(key) ? this.map.get(key)! : null;
  }

  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }

  removeItem(key: string): void {
    this.map.delete(key);
  }
}

type WindowStub = {
  location: { pathname: string; search: string; assign: Mock };
  setTimeout: Mock;
};

let storage: MemoryStorage;
let windowStub: WindowStub;
let consoleError: MockInstance;

beforeEach(() => {
  storage = new MemoryStorage();
  windowStub = {
    location: { pathname: '/samples', search: '?page=2', assign: vi.fn() },
    setTimeout: vi.fn(),
  };
  vi.stubGlobal('sessionStorage', storage);
  vi.stubGlobal('window', windowStub);
  vi.stubGlobal('requestAnimationFrame', vi.fn());
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  consoleError.mockRestore();
  vi.useRealTimers();
});

/**
 * Fresh module instance (fresh dedupe map / recovering flag / clear latch) plus its mocks.
 * vi.resetModules() re-evaluates the policy but NOT the vi.mock factories (the mocks
 * registry survives it), so the show spy is shared across loads and must be cleared here.
 */
async function loadPolicy() {
  vi.resetModules();
  const policy = await import('../../main/frontend/util/rpcErrorPolicy.js');
  const { Notification } = await import('@vaadin/react-components/Notification.js');
  const show = Notification.show as unknown as Mock;
  show.mockClear();
  // Hilla's Middleware is a MiddlewareFunction | MiddlewareClass union; rpcErrorPolicy is
  // the function form, but TS cannot call the union — narrow once here.
  const run = policy.rpcErrorPolicy as (context: MiddlewareContext, next: MiddlewareNext) => Promise<Response>;
  return { policy, run, show };
}

function ctx(endpoint: string, method = 'list'): MiddlewareContext {
  return { endpoint, method } as unknown as MiddlewareContext;
}

function respond(status: number, body?: unknown): MiddlewareNext {
  return async () => new Response(body === undefined ? '' : JSON.stringify(body), { status });
}

const ok: MiddlewareNext = async () => new Response('{}', { status: 200 });

describe('status mapping', () => {
  it('network failure (next rejects) toasts a reachability message and rethrows', async () => {
    const { run, show } = await loadPolicy();
    const failure = new Error('socket hang up');
    const next: MiddlewareNext = async () => {
      throw failure;
    };

    await expect(run(ctx('SampleService'), next)).rejects.toThrow(failure);

    expect(show).toHaveBeenCalledExactlyOnceWith(
      'Cannot reach the server (SampleService.list). Check the connection and try again.',
      { duration: 6000, theme: 'error' },
    );
  });

  it('500 with a server message shows that message as a transient toast', async () => {
    const { run, show } = await loadPolicy();

    const response = await run(ctx('SampleService', 'save'), respond(500, { message: 'boom from server' }));

    expect(response.status).toBe(500);
    expect(show).toHaveBeenCalledExactlyOnceWith('boom from server', { duration: 6000, theme: 'error' });
  });

  it('500 without a body falls back to the generic message', async () => {
    const { run, show } = await loadPolicy();

    await run(ctx('SampleService'), respond(500));

    expect(show).toHaveBeenCalledExactlyOnceWith(
      'The server could not complete SampleService.list (HTTP 500).',
      { duration: 6000, theme: 'error' },
    );
  });

  it('403 is persistent (duration 0) and wires click-to-dismiss', async () => {
    const { run, show } = await loadPolicy();

    await run(ctx('UserService', 'save'), respond(403));

    expect(show).toHaveBeenCalledExactlyOnceWith('Not permitted: UserService.save.', {
      duration: 0,
      theme: 'error',
    });
    expect(requestAnimationFrame).toHaveBeenCalledOnce();
  });

  it('403 with a server-provided message shows that message, still persistent', async () => {
    const { run, show } = await loadPolicy();

    await run(ctx('UserService', 'save'), respond(403, { message: 'Only admins may manage users' }));

    expect(show).toHaveBeenCalledExactlyOnceWith('Only admins may manage users', {
      duration: 0,
      theme: 'error',
    });
  });

  it('a non-JSON error body falls back to the generic message instead of throwing', async () => {
    const { run, show } = await loadPolicy();
    const next: MiddlewareNext = async () => new Response('<html>Bad Gateway</html>', { status: 502 });

    const response = await run(ctx('SampleService'), next);

    expect(response.status).toBe(502);
    expect(show).toHaveBeenCalledExactlyOnceWith(
      'The server could not complete SampleService.list (HTTP 502).',
      { duration: 6000, theme: 'error' },
    );
  });

  it('leaves the error body readable after the policy ran (clone-first contract)', async () => {
    const { run } = await loadPolicy();

    const response = await run(ctx('SampleService'), respond(500, { message: 'boom' }));

    // Connect.js#assertResponseIsOk awaits response.text() one level up the chain, so
    // readErrorBody must read a clone() and leave the body itself unread.
    await expect(response.text()).resolves.toBe(JSON.stringify({ message: 'boom' }));
  });

  it('validation errors pass through silently — autoform renders them into the form', async () => {
    const { run, show } = await loadPolicy();

    const response = await run(
      ctx('SampleService', 'save'),
      respond(400, { message: 'Validation error', validationErrorData: [{ parameterName: 'name' }] }),
    );

    expect(response.status).toBe(400);
    expect(show).not.toHaveBeenCalled();
  });

  it('never logs context.params (UserService.save carries newPassword)', async () => {
    const { run } = await loadPolicy();
    const context = { endpoint: 'UserService', method: 'save', params: [{ newPassword: 'hunter2' }] };

    await run(context as unknown as MiddlewareContext, respond(500));

    expect(consoleError).toHaveBeenCalledOnce();
    expect(JSON.stringify(consoleError.mock.calls[0])).not.toContain('hunter2');
  });
});

describe('repeat suppression', () => {
  it('suppresses the same status+endpoint+method while the previous toast is still open', async () => {
    const { run, show } = await loadPolicy();

    await run(ctx('SampleService'), respond(500));
    await run(ctx('SampleService'), respond(500));

    expect(show).toHaveBeenCalledOnce();
  });

  it('a different endpoint or a different status each get their own toast', async () => {
    const { run, show } = await loadPolicy();

    await run(ctx('SampleService'), respond(500));
    await run(ctx('ProjectService'), respond(500));
    await run(ctx('SampleService'), respond(503));

    expect(show).toHaveBeenCalledTimes(3);
  });

  it('after the toast closes, repeats stay suppressed inside the 5s window and fire after it', async () => {
    vi.useFakeTimers();
    const t0 = new Date('2026-08-30T12:00:00Z');
    vi.setSystemTime(t0);
    const { run, show } = await loadPolicy();

    await run(ctx('SampleService'), respond(500));
    expect(show).toHaveBeenCalledOnce();
    (show.mock.results[0]!.value as { opened: boolean }).opened = false;

    vi.setSystemTime(new Date(t0.getTime() + 3000));
    await run(ctx('SampleService'), respond(500));
    expect(show).toHaveBeenCalledOnce();

    vi.setSystemTime(new Date(t0.getTime() + 6000));
    await run(ctx('SampleService'), respond(500));
    expect(show).toHaveBeenCalledTimes(2);
  });
});

describe('401 session recovery', () => {
  it('first 401 toasts, sets the reload marker, and reloads the current path after the delay', async () => {
    const { run, show } = await loadPolicy();

    const response = await run(ctx('SampleService'), respond(401));

    expect(response.status).toBe(401);
    expect(show).toHaveBeenCalledExactlyOnceWith(SESSION_TOAST, {
      duration: 1500,
      position: 'middle',
      theme: 'error',
    });
    expect(Number(storage.getItem(RELOAD_MARKER))).toBeGreaterThan(0);

    expect(windowStub.setTimeout).toHaveBeenCalledExactlyOnceWith(expect.any(Function), 1500);
    (windowStub.setTimeout.mock.calls[0]![0] as () => void)();
    expect(windowStub.location.assign).toHaveBeenCalledExactlyOnceWith('/samples?page=2');
  });

  it('a second 401 while recovery is in flight schedules nothing further', async () => {
    const { run, show } = await loadPolicy();

    await run(ctx('SampleService'), respond(401));
    await run(ctx('ProjectService'), respond(401));

    expect(show).toHaveBeenCalledOnce();
    expect(windowStub.setTimeout).toHaveBeenCalledOnce();
  });

  it('a 401 after the reload (marker present) goes to /login instead of looping', async () => {
    const first = await loadPolicy();
    await first.run(ctx('SampleService'), respond(401));
    expect(storage.getItem(RELOAD_MARKER)).not.toBeNull();

    // Fresh module = the page the recovery reload produced; sessionStorage survives it.
    const second = await loadPolicy();
    await second.run(ctx('SampleService'), respond(401));

    expect(windowStub.setTimeout).toHaveBeenCalledTimes(2);
    (windowStub.setTimeout.mock.calls[1]![0] as () => void)();
    expect(windowStub.location.assign).toHaveBeenCalledExactlyOnceWith('/login');
  });

  it('an expired marker (past its 30s TTL) permits one more reload attempt', async () => {
    storage.setItem(RELOAD_MARKER, String(Date.now() - 31_000));
    const { run } = await loadPolicy();

    await run(ctx('SampleService'), respond(401));

    (windowStub.setTimeout.mock.calls[0]![0] as () => void)();
    expect(windowStub.location.assign).toHaveBeenCalledExactlyOnceWith('/samples?page=2');
  });

  it('does nothing on the login page itself', async () => {
    windowStub.location.pathname = '/login';
    const { run, show } = await loadPolicy();

    await run(ctx('SampleService'), respond(401));

    expect(show).not.toHaveBeenCalled();
    expect(windowStub.setTimeout).not.toHaveBeenCalled();
    expect(storage.getItem(RELOAD_MARKER)).toBeNull();
  });

  it('while recovering, failures from other calls are not toasted', async () => {
    const { run, show } = await loadPolicy();

    await run(ctx('SampleService'), respond(401));
    await run(ctx('ProjectService'), respond(500));

    expect(show).toHaveBeenCalledExactlyOnceWith(SESSION_TOAST, expect.anything());
  });

  it('recovery still works when sessionStorage throws (treated as no marker)', async () => {
    const broken = {
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('denied');
      },
      removeItem: () => {
        throw new Error('denied');
      },
    };
    vi.stubGlobal('sessionStorage', broken);
    const { run, show } = await loadPolicy();

    await run(ctx('SampleService'), respond(401));

    expect(show).toHaveBeenCalledExactlyOnceWith(SESSION_TOAST, expect.anything());
    (windowStub.setTimeout.mock.calls[0]![0] as () => void)();
    expect(windowStub.location.assign).toHaveBeenCalledExactlyOnceWith('/samples?page=2');
  });
});

describe('auth endpoint exemption', () => {
  it('401 from UserEndpoint never triggers recovery and is fully silent', async () => {
    const { run, show } = await loadPolicy();

    const response = await run(ctx('UserEndpoint', 'getAuthenticatedUser'), respond(401));

    expect(response.status).toBe(401);
    expect(windowStub.setTimeout).not.toHaveBeenCalled();
    expect(storage.getItem(RELOAD_MARKER)).toBeNull();
    // The mount-time auth probe answering 401 is the normal anonymous state: no toast of any
    // kind and no console noise — report() is skipped entirely.
    expect(show).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('a 200 from UserEndpoint does not clear the reload marker (it answers 200 even logged out)', async () => {
    storage.setItem(RELOAD_MARKER, String(Date.now()));
    const { run } = await loadPolicy();

    await run(ctx('UserEndpoint', 'getAuthenticatedUser'), ok);

    expect(storage.getItem(RELOAD_MARKER)).not.toBeNull();
  });
});

describe('reload marker clearing', () => {
  it('the first OK response from a non-auth endpoint clears the marker, once', async () => {
    storage.setItem(RELOAD_MARKER, String(Date.now()));
    const removeItem = vi.spyOn(storage, 'removeItem');
    const { run } = await loadPolicy();

    await run(ctx('SampleService'), ok);
    expect(storage.getItem(RELOAD_MARKER)).toBeNull();

    await run(ctx('SampleService'), ok);
    expect(removeItem).toHaveBeenCalledOnce();
  });
});

describe('createConnectClient', () => {
  it('builds the client with the mandatory prefix and this policy as middleware', async () => {
    const mod = await loadPolicy();

    const client = mod.policy.createConnectClient() as unknown as { options: { prefix: string; middlewares: unknown[] } };

    expect(client.options.prefix).toBe('connect');
    expect(client.options.middlewares).toEqual([mod.policy.rpcErrorPolicy]);
  });
});
