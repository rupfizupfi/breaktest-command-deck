# State & real-time data flow

## Purpose

Trace a single sensor reading from the load cell on the bench all the way to
a pixel in the chart in `views/run.tsx` — how the deck app combines **synchronous Hilla RPC** (start/stop the test) with
**asynchronous STOMP/WebSocket pushes** (the firehose of measurements).
Together with [`hilla-generated-layer.md`](hilla-generated-layer.md) this
answers *"how does a button click in React reach a Java method, and how does a
sensor reading reach a chart?"*

## Contents

- [Diagram — STOMP sequence (button click + sensor stream)](#diagram--stomp-sequence-button-click--sensor-stream)
- [Narrative](#narrative)
  - [WebSocket topology](#websocket-topology)
  - [Data flow for `views/run.tsx`](#data-flow-for-viewsruntsx)
  - [Why the par block matters](#why-the-par-block-matters)
- [Where to look in the code](#where-to-look-in-the-code)
- [Known limits](#known-limits)

## Diagram — STOMP sequence (button click + sensor stream)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant ChartCmp as LiveTestResult.tsx<br/>(react-chartjs-2)
    participant Status as StatusService.ts<br/>(@stomp/rx-stomp singleton)
    participant Hilla as Hilla generated<br/>TestRunnerService.ts
    participant Spring as Spring MVC /<br/>Hilla dispatcher
    participant Runner as TestRunnerService.java<br/>@BrowserCallable
    participant Thread as TestRunnerThread.java<br/>(plain class that owns a java.lang.Thread)
    participant LCThread as MeasurementLoop.java
    participant Device as LoadCellDevice<br/>(dscusb.jar serial)
    participant Broadcast as ForceBroadcaster.java<br/>(MeasurementObserver)
    participant Broker as Spring SimpleBroker<br/>/topic/load-cell

    User->>ChartCmp: Click "Run test"
    ChartCmp->>Hilla: TestRunnerService.start(testId)
    Hilla->>Spring: POST /connect/TestRunnerService/start
    Spring->>Runner: start(testId) [@PermitAll]
    Runner->>Thread: testRunnerThread.startThread(testResult)
    Thread-->>Runner: returns immediately (async)
    Runner-->>Hilla: 200 OK (void)
    Hilla-->>ChartCmp: Promise<void>

    par WebSocket already connected
        ChartCmp->>Status: connectComponent(this) +<br/>loadCellObservable.subscribe(...)
        Status->>Broker: STOMP CONNECT<br/>(ws://host/status)
        Status->>Broker: SUBSCRIBE /topic/load-cell
    and Hardware loop
        Thread->>LCThread: starts inside test setup()
        loop continuous sampling
            Device-->>LCThread: Measurement(timestamp, force)
            LCThread->>Broadcast: update(measurements)
            Broadcast->>Broker: convertAndSend("/topic/load-cell", buf)<br/>(flushes when the oldest buffered sample is<br/>&gt;60 ms old, tested on the 20 ms reader tick)
        end
    end

    Broker-->>Status: STOMP MESSAGE frame
    Status-->>ChartCmp: IMessage (rxjs Observable)
    ChartCmp->>ChartCmp: ingest(buffer, parseBatch(body))<br/>bounded trace buffer, chart.update('none')

    User->>ChartCmp: Click "Stop"
    ChartCmp->>Hilla: TestRunnerService.stop()
    Hilla->>Runner: stop()
    Runner->>Thread: stopThread() / interrupt()

    User->>ChartCmp: Click "Close" (unmounts the chart)
    ChartCmp->>Status: disconnectComponent(this)
    Status->>Broker: DISCONNECT (only once the last consumer has left)
```

(Also at [`doc/diagrams/src/stomp-sequence.mmd`](../../diagrams/src/stomp-sequence.mmd).
A separate Hilla-only round-trip diagram lives at
[`doc/diagrams/src/hilla-rpc.mmd`](../../diagrams/src/hilla-rpc.mmd) and is
embedded in [`hilla-generated-layer.md`](hilla-generated-layer.md).)

## Narrative

### WebSocket topology

`cms/src/main/java/ch/rupfizupfi/deck/messaging/WebSocketConfig.java` (the
only messaging `@Configuration`) registers an in-memory simple broker:

```java
config.enableSimpleBroker("/topic");
config.setApplicationDestinationPrefixes("/app");
registry.addEndpoint("/status");
registration.interceptors(new BrokerInboundGuard()); // configureClientInboundChannel
```

One STOMP endpoint, `/status`, without SockJS fallback. It needs its own HTTP
rule: `VaadinSecurityConfigurer` closes the filter chain
with `anyRequest().denyAll()`, so anything that is neither a Vaadin route nor a
Hilla endpoint needs an authorization rule of its own, and
`SecurityConfiguration` grants exactly one — `/status`, `authenticated()` (see
[security-and-tenancy](../backend/security-and-tenancy.md)).
Every topic, the log topic included, is delivered over that one connection.

The browser only listens. `BrokerInboundGuard` admits a SUBSCRIBE under
`/topic/` from a session with a user and refuses every SEND, so no client can
publish a frame other operators would see; a refusal comes back as a STOMP
ERROR frame. `StatusService` accordingly has no publish method.

Three topics are pushed:

| Topic | Producer | Frame body |
|---|---|---|
| `/topic/load-cell` | `device/loadcell/ForceBroadcaster.java` | JSON array of `{timestamp, force}` measurements, flushed once the buffer's oldest sample passes 60 ms (tested on the 20 ms reader tick, so not a fixed rate) and again when the load-cell device closes, so a run's tail never opens the next run's chart |
| `/topic/frequency-inverter-info` | `device/frequencyinverter/DeviceInfoBroadcaster.java` | JSON `Info` object (speed, motor current/voltage/torque, ...) |
| `/topic/logs` | `testrunner/TestLogger.java` (subscribed to via `Status.logObservable`) | plain string per log line |

The frontend's STOMP singleton is `command-deck/src/main/frontend/service/StatusService.ts`
— a hand-written wrapper around `@stomp/rx-stomp`'s `RxStomp`. It exposes
three rxjs `Observable<IMessage>`s (`loadCellObservable`,
`frequencyInverterInfoObservable`, `logObservable`) and a refcount
(`connectComponent` / `disconnectComponent`) that activates the underlying
WebSocket only while at least one component cares. The refcount is the only
way to open or close the socket: `connect()`/`disconnect()` are private, so no
consumer can close it under another one. It also publishes a `liveStatus`
observable — socket state plus a per-topic staleness deadline, so a frozen
number is distinguishable from a live one. The rules behind both — freshness,
test-state ordering, warning precedence, the refcount — are pure functions in
`command-deck/src/main/frontend/service/connectionState.ts`, unit-tested in
`command-deck/src/test/frontend/connectionState.test.ts`.

Socket consumers, each holding one reference while mounted: the Deck view
(`views/@index.tsx`), the control board, the run chart (`TestResultGraph`) and
`InfoBoard` while its "Show status" box is ticked. The Deck view's Start/Stop
take and release its own hold on the server's broadcasting
(`DeviceInfoService.enable/disable(clientId)`, per view per page load), never the
socket; InfoBoard holds separately, so either can leave without blinding the other.

The broker URL is derived from the page, not configured
(`command-deck/src/main/frontend/service/StatusService.ts:22-27`):
`new URL('/status', document.baseURI)` with the scheme swapped to `wss:`
whenever the page is `https:`, so telemetry works behind TLS in `docker` and
on plain `http://localhost:8080` in dev. The leading slash matters — the
service can first be constructed from a nested route, whose base directory is
not the server root.

### Data flow for `views/run.tsx`

`command-deck/src/main/frontend/views/run.tsx` is the test-execution
view. Its lifecycle:

1. **Initial fetch (synchronous, via Hilla).** The view is built around an
   `<AutoCrud>` (imported via the cms alias) bound to `TestResultService`,
   `TestParameterService`, `SampleService` — all auto-generated TS
   clients. AutoCrud paginates the grid and renders the form via
   `TestResultModel`. No live data yet.
2. **Run is enabled only for a saved, unedited record.** `start()` runs the
   stored row, so AutoCrud's `onEditStateChange` reports the selected item and
   whether the form is dirty; "Run test" stays disabled for a new or copied
   item (no `id`) and while the form holds unsubmitted edits.
3. **Mount the live chart.** "Run test" calls
   `setTestResultData(readyTestResultData)`. That prop change re-renders
   `<LiveTestResult/>` with a non-null `testResult`.
4. **`LiveTestResult` resolves the test.**
   `command-deck/src/main/frontend/components/dashboard/LiveTestResult.tsx` decides whether to use the
   user-clicked test or recover an already-running one via
   `TestRunnerService.status()`. If anything is running, a `<TestResultGraph/>`
   is mounted.
5. **`TestResultGraph` sets up the stream.** On mount:
   - subscribes to `loadCellObservable` — each `IMessage.body` is a JSON array of
     `{timestamp, force}` samples handed to `ingest` in
     `components/dashboard/traceBuffer.ts`, which appends them to the single point
     array Chart.js holds by reference and evicts a block at a time once the run
     fills it.
   - subscribes to `logObservable` &rarr; `appendLog` in
     `cms/src/main/frontend/components/dashboard/logBuffer.ts`, which keeps the
     newest lines (`DEFAULT_LOG_CAPACITY`) and counts the rest, shown as
     "N earlier lines hidden".
   - calls `TestRunnerService.start(testResult.id!)` — the "go" signal — only
     in the view that issued Run (`startOnMount`); a view that found the run
     through `status()` only watches. The Hilla call returns void; the STOMP
     stream is the proof of life.
   - calls `service.connectComponent(TestResultGraph)` to refcount-activate
     the WebSocket.
6. **Teardown.** The same `useEffect` returns a cleanup that
   `unsubscribe()`s both observables and calls `disconnectComponent`. The
   socket closes only when that was the last consumer; leaving `/run` while
   `InfoBoard` still shows status keeps it open.
7. **Stop & close buttons.** Stop awaits `TestRunnerService.stop()` and stays
   armed if the call fails; Close on a live run asks first and stops only this
   run (`stopRun(id)`), then `reset()` unmounts `<LiveTestResult/>`.
8. **Render failures stay local.** The chart and log sit in their own
   `ErrorBoundary` (`cms/src/main/frontend/components/ErrorBoundary.tsx`), so a
   chart crash leaves Stop, Close and `TestIncidentBanner` mounted.

**Holes in the trace.** `ingest` inserts one `y: NaN` separator whenever
consecutive samples sit more than `GAP_MS` apart — the interval
`MeasurementLoop`'s no-data watchdog uses, so every break the chart draws is one
the backend would also declare. `spanGaps` stays `false`, so the line breaks
there and nothing is drawn across an interval where nothing was measured. The
separator moves neither the running max nor the last value, so the Force/Max
readouts never see a NaN. Min-max decimation runs only because the chart sets
`parsing: false` — Chart.js returns from the plugin before decimating whenever
parsing is on, and the buffer already holds the `{x, y}` points parsing would
otherwise produce. It engages past 4× the canvas width in points and keeps only
each x bucket's ends, min and max: a separator sharing a bucket with real
samples is none of those, so a sub-pixel hole in a long trace can lose its
break.

**One parser for the batch.** Every `/topic/load-cell` consumer — `InfoBoard`,
the Deck view, the control board and `LiveTestResult` — reads the payload through
`command-deck/src/main/frontend/service/loadCellBatch.ts`, which pins the
`force`/`timestamp` wire keys on the frontend and yields an empty batch for a
body that is not a JSON array or not JSON at all, so a subscription callback never throws. An empty
batch yields no force at all, so a readout keeps its last value.

### Why the par block matters

In the sequence diagram above, the WebSocket subscribe and the Hilla
`start()` call run in parallel. `TestRunnerThread.run` covers that with a
`Thread.sleep(50)` before doing anything, coupling the RPC to the WebSocket
lifecycle (OQ-23).

## Where to look in the code
- `command-deck/src/main/frontend/views/run.tsx`
- `command-deck/src/main/frontend/components/dashboard/LiveTestResult.tsx`
- `command-deck/src/main/frontend/components/dashboard/traceBuffer.ts` (the trace buffer and its gap separator; unit-tested in `command-deck/src/test/frontend/traceBuffer.test.ts`)
- `command-deck/src/main/frontend/components/dashboard/InfoBoard.tsx` (`/topic/frequency-inverter-info` consumer)
- `command-deck/src/main/frontend/service/loadCellBatch.ts` (the `/topic/load-cell` batch parser; unit-tested in `command-deck/src/test/frontend/loadCellBatch.test.ts`)
- `command-deck/src/main/frontend/service/StatusService.ts` and its pure rules in `command-deck/src/main/frontend/service/connectionState.ts`
- `cms/src/main/frontend/components/dashboard/logBuffer.ts` (the capped run log; unit-tested in `cms/src/test/frontend/logBuffer.test.ts`)
- `command-deck/src/main/java/.../api/services/TestRunnerService.java:11-44`
- `command-deck/src/main/java/.../testrunner/TestRunnerThread.java:23-78`
- `command-deck/src/main/java/.../testrunner/measure/MeasurementLoop.java`
- `command-deck/src/main/java/.../device/loadcell/ForceBroadcaster.java:9-25`
- `cms/src/main/java/.../messaging/WebSocketConfig.java:10-29` and `BrokerInboundGuard.java`
- `cms/src/main/java/.../security/SecurityConfiguration.java` (the `/status` handshake rule)

## Known limits

- The 50 ms sleep in `TestRunnerThread.run()` is a soft race: a cold tab or a loaded CPU takes longer than that to subscribe, and the first measurements are dropped with no indication (OQ-23).
