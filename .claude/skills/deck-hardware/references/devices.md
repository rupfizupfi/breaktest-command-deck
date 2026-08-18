# Adding a hardware device

All device code lives in `command-deck/src/main/java/ch/rupfizupfi/deck/device/`. Copy `device/frequencyconverter/` — the most complete example.

## Checklist

```
- [ ] 1. device/<name>/<Name>Device.java extends Device
- [ ] 2. device/<name>/<X>Observer.java (one-method interface)
- [ ] 3. device/<name>/<X>Broadcaster.java implements observer → STOMP topic
- [ ] 4. DTO with public fields (like frequencyconverter/Info.java)
- [ ] 5. Wire into device/DeviceService.java
- [ ] 6. Optional @BrowserCallable toggle service in api/services/
- [ ] 7. Frontend subscription in StatusService.ts
```

## 1. Device subclass

`Device` is a **ref-counted connection** base class: first `connect()` calls your `openConnection()`, last `disconnect()` calls `closeConnection()`. Implement `openConnection()`, `closeConnection()`, `getHardwareComponent()`.

Polling convention: own `Thread` + `volatile boolean isRunning`, observers in a `CopyOnWriteArrayList<XObserver>`, with `registerObserver`/`unregisterObserver`/`notifyObservers`. `CFW11Device` lazily starts/stops the poll thread based on observer count (`tryStartThread`/`tryStopThread`) — prefer that. Poll intervals in use: load cell 20 ms, CFW11 400 ms.

Serial via jSerialComm: see `device/relayswitch/FourWayRelaySwitch.java` (115200 8N1, port discovery by `getDescriptivePortName().contains(...)` — throw `ComportNotFoundException` when absent). Note that substring port matching binds by USB chipset name, not device identity; document which adapter the match assumes.

## 3. Broadcaster

Takes `SimpMessagingTemplate`, sends to `/topic/<name>`:

```java
template.convertAndSend("/topic/my-device", payload);
```

If broadcasting is high-frequency, batch like `ForceBroadcaster` (accumulates until >60 ms elapsed).

## 5. Wiring

`DeviceService` (`@Service`, singleton) constructs devices and broadcasters in its constructor and exposes getters. Add your device there; expose enable/disable methods that `connect()`/`disconnect()` and register/unregister the broadcaster (see `enableInfoBroadcasting()`).

## 6. Toggle service (optional)

`@BrowserCallable @PermitAll` service delegating to `DeviceService`, like `api/services/DeviceInfoService.java`. Beware: these hold plain mutable flags with no concurrency guard — two clients can fight; keep methods idempotent.

## 7. Frontend

Add a `watch('/topic/my-device')` observable to `command-deck/src/main/frontend/service/StatusService.ts` (the ref-counted RxStomp singleton) and consume it from a component with `connectComponent()`/`disconnectComponent()` in `useEffect` (see `components/dashboard/InfoBoard.tsx`).
