> Branch: `dev-split` — external research captured 2026-08-17.
> **Nothing evaluated here is adopted** — see [`README.md`](README.md).

# Backend test harness — what would work on Java 26 + Boot 4.1

**Status: nothing adopted.** `CLAUDE.md` states there are no tests, and that is
still true. `cms/src/test` and `command-deck/src/test` exist and are **empty**.

One thing is already in place: `spring-boot-starter-test` is declared for every
subproject in the root `build.gradle`. **No dependency work blocks a first
test** — only the decision, which is the blocker recorded against **OQ-32**.

---

## Contents

- [The single most useful artifact: the Boot 4.1.0 BOM](#the-single-most-useful-artifact-the-boot-410-bom)
- [Landmines, in order of how much time they cost](#landmines-in-order-of-how-much-time-they-cost)
- [Tool compatibility](#tool-compatibility)
- [Testing the thread-heavy code](#testing-the-thread-heavy-code)
- [STOMP over WebSocket](#stomp-over-websocket)
- [Suggested order for a first suite](#suggested-order-for-a-first-suite)
- [Starting dependency set](#starting-dependency-set)

## The single most useful artifact: the Boot 4.1.0 BOM

Managed versions, read from `spring-boot-dependencies-4.1.0.pom`. If dependency
management is used, these are inherited and most compatibility questions answer
themselves (all **HIGH**):

| Property | Version | Note |
|---|---|---|
| `junit-jupiter.version` | **6.0.3** | **JUnit 6, not 5.** JUnit 4 is retired in Boot 4 |
| `assertj.version` | 3.27.7 | |
| `mockito.version` | 5.23.0 | see the byte-buddy warning below |
| `byte-buddy.version` | **1.18.10** | **do not override** |
| `awaitility.version` | 4.3.0 | **already on the test classpath** |
| `testcontainers.version` | **2.0.5** | modules were all renamed — see below |
| `jackson-bom.version` | 3.1.4 | Jackson 3; 2.21.4 still managed alongside |

## Landmines, in order of how much time they cost

**1. Never override or exclude Boot's `byte-buddy` version.** Mockito 5.23.0's
POM pins byte-buddy **1.17.7**. Java 26 class-file support landed in
**1.17.8** — one patch later. The BOM forces 1.18.10, which is the only reason
mocking works at all. Declare Mockito outside the BOM and the symptom is
`Unsupported class file major version 70`. The fix is always the byte-buddy
version, never `-Dnet.bytebuddy.experimental=true`. (**HIGH** with the BOM
applied; **MEDIUM-LOW** for standalone Mockito.)

**2. JEP 500 "Prepare to Make Final Mean Final" is *delivered* in JDK 26.**
Mutating a `final` field by deep reflection now warns at runtime, and
**`--add-opens` will not silence it** — the usual reflex fix does not apply.
This hits `ReflectionTestUtils.setField()` and Mockito field injection into
final fields. Still only a warning today; a future JDK makes it an exception.
*Design consequence: prefer constructor injection in code being made testable* —
which converges with the seven-`new`-sites problem in
[`hardware-simulation.md`](hardware-simulation.md).

**3. Testcontainers 2.0 renamed every module.** Verified against Maven Central:
`org.testcontainers:postgresql:2.0.5` returns **404**;
`org.testcontainers:testcontainers-postgresql:2.0.5` returns **200**. Same for
`testcontainers-junit-jupiter`. JUnit 4 support was removed outright. **Every
pre-2026 tutorial has coordinates that will not resolve.**

**4. Boot 4 test-API changes that break copy-pasted examples** (all **HIGH**,
from the Boot 4.0 migration guide and Framework 7 release notes):

- `@MockBean` / `@SpyBean` are **removed**, not deprecated → `@MockitoBean` /
  `@MockitoSpyBean`. They can no longer be declared on `@Configuration` classes.
- **`@SpringBootTest` no longer auto-configures MockMvc or TestRestTemplate** —
  add `@AutoConfigureMockMvc` explicitly. Silently produces "why is my
  `MockMvc` null" on day one.
- MockMvc slices now need **`spring-boot-starter-webmvc-test`**;
  `spring-boot-starter-test` no longer covers everything.
- `RestTestClient` is the new fluent client, successor to `TestRestTemplate`.
- `MappingJackson2MessageConverter` is **deprecated for removal** →
  `JacksonJsonMessageConverter`. Every STOMP example online uses the old one.
- `@Transactional` does **not** roll back under `webEnvironment=RANDOM_PORT` —
  client and server run on different threads and transactions.
- Useful here: **test-context pausing** (`spring.test.context.cache.pause`)
  exists specifically to stop background processes in cached-but-unused
  contexts from fighting over resources. Directly relevant — device threads and
  broadcasters would otherwise keep running in stale contexts and contend for
  the USB devices.

**5. Structured concurrency is NOT final in Java 26.** JEP 525 is the *sixth*
preview; JEP 533 in JDK 27 is the seventh. Several 2026 posts claim otherwise.
`--enable-preview` is required. **Do not put a preview API in the control path
of a machine that destroys material.** Virtual threads (final since 21) also
buy nothing here: jSerialComm's blocking native calls pin carrier threads.

## Tool compatibility

| Tool | Version | Java 26 | Confidence |
|---|---|---|---|
| JaCoCo | 0.8.15 | ✅ **officially** listed | HIGH — unusually, it is ahead of the JDK this cycle |
| ArchUnit | 1.5.0 | ✅ since 1.4.2 | HIGH — **needs `archunit-junit6`, not `archunit-junit5`** |
| Pitest | 1.25.9 | probably | **MEDIUM** — inferred from its ASM 9.10.1 bump; no release note claims it |
| Awaitility | 4.3.0 | ✅ | HIGH — ⚠️ the project's changelog announces 4.3.1 but **4.3.1 was never published to Central**. Pin 4.3.0 |
| jcstress / Lincheck / Fray | — | — | **Wrong shape.** JMM stress tools for lock-free data structures; they cannot express "motor must stop when the force limit trips". Fray additionally needs an instrumented JDK, with only JDK 25 evidenced |

## Testing the thread-heavy code

`TestRunnerThread`, `LoadCellThread` and `SignalListener` each conflate three
things: a **state machine** (force exceeded ⇒ stop; cycle count reached ⇒
finish), **I/O adapters**, and a **threading shell** (loop, sleep, join).

**The highest-value refactor needs no library.** Extract the state machine into
a plain class taking events in (`onSample(force, instant)`) and returning
decisions out (`Decision.STOP`), then test it single-threaded in microseconds.
That converts most of the actual risk in machine control into ordinary unit
tests, and has no Java-26 compatibility question at all. It is also the
precondition for most of the
[`../06-feature-work/testrunner-safety/audit-findings.md`](../06-feature-work/testrunner-safety/audit-findings.md)
findings being testable — C7 (only the last measurement of each batch is
checked) is a pure-logic bug that a single unit test would pin.

Inject a `Clock` while doing it (~1 hour; `Clock.fixed` plus a small
`MutableClock`). Use Awaitility only at the outermost seam.

## STOMP over WebSocket

**There is no MockMvc analog for messaging, none in Framework 7, and none
coming in 7.1.** The Framework 7 release notes contain zero occurrences of
"STOMP", and the testing chapter has no messaging section. Do not wait for one.
(**HIGH**.) The documented approach is unchanged since Framework 4.x.

Three tiers, cheapest first:

1. **Standalone `@MessageMapping` tests** — hand-wire
   `SimpAnnotationMethodMessageHandler` with test channels, dispatch messages,
   assert on captured output. No context, no server, millisecond runtime.
2. **Mock `SimpMessagingTemplate` for the broadcasters.** `ForceBroadcaster` and
   `DeviceInfoBroadcaster` are plain beans calling `convertAndSend`;
   `ArgumentCaptor` on destination and payload. **This is where the real bugs
   are** — wrong topic, wrong units, nulls under sensor dropout — and it needs
   zero Spring infrastructure.
3. **Exactly one end-to-end test** as a wiring smoke check. Do not grow a suite
   here.

Gotchas that cost the most time: Spring Security's **CSRF token must travel in
the STOMP `CONNECT` frame's own headers**, not HTTP headers; `connect()`
returning `ListenableFuture` is gone (only `connectAsync()`);
`setInboundMessageSizeLimit` defaults to **64 KB**, so large sensor batches fail
with a confusing STOMP error rather than an assertion failure; and if
`StompFrameHandler.getPayloadType()` returns a type the converter cannot
produce, `handleFrame` **silently never fires** and you get an opaque timeout.

---

## Suggested order for a first suite

The sources genuinely disagree. rieckpil's Jan-2026 article explicitly refuses
to prescribe an ordering; the same author's Mar-2026 piece gives unit → slice →
`@SpringBootTest` but **states it offers no guidance for retrofitting existing
applications**. For a zero-test brownfield app the legacy-code camp (Feathers)
wins: you do not yet know which internal units are correct, dead, or
load-bearing, and unit tests written against inherited structure are the first
casualties of the refactor that follows. **The pyramid is the destination, not
the route.**

1. **`contextLoads` per module** — under an hour. Assert specific beans exist
   (`TestRunnerFactory`, `SimpMessagingTemplate`, the `@BrowserCallable`
   services) rather than leaving the body empty; an empty method proves only
   that nothing threw. Solves test-profile and datasource setup once, for
   everything after.
2. **Characterization tests on the extracted decision logic** — the pinch
   point: where the domain lives and where a bug destroys a specimen.
3. **Broadcaster tests** (tier 2 above).
4. **Standalone `@MessageMapping` tests** (tier 1 above).
5. **Service-boundary tests on the `@BrowserCallable` services** — call the
   *bean* from `@SpringBootTest`, not over HTTP. `@CheckUserCanOnlyAccessOwnData`
   applies to the proxied bean, so an injected-bean call still exercises
   ownership while skipping Hilla's serialization. ⚠️ Verify with one
   deliberately-failing case first: Spring AOP proxies do **not** apply on
   self-invocation. `@WebMvcTest` is the wrong slice — Hilla endpoints are not
   `@Controller`s. This is the shape that would settle **OQ-37**.
6. **Testcontainers PostgreSQL *before* writing many repository tests.** The
   dev/prod split is H2/Postgres; writing a suite against H2 semantics and then
   rewriting it is the predictable failure. This is what **OQ-32** needs.
7. **Only then, unit tests of internals** — and only for code being changed.

Worth doing literally, per Carlo's three-step method: snapshot the output, use
coverage to find unexecuted branches, then **mutate the code (comment out a
line) to prove each test actually fails.** A surviving mutation means the inputs
are insufficient. Pitest mechanizes step 3; do it by hand first.

## Starting dependency set

Versions inherited from the BOM — do not pin manually:

- `spring-boot-starter-test`
- `spring-boot-starter-webmvc-test` — only if MockMvc slices are added
- `org.testcontainers:testcontainers-junit-jupiter` and
  `org.testcontainers:testcontainers-postgresql` — note the prefixes
- JaCoCo 0.8.15 — safe to enable immediately
- `com.tngtech.archunit:archunit-junit6:1.5.0` — optional, later

Sources: [Boot 4.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html) ·
[Boot 4.0 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) ·
[Framework 7.0 release notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes) ·
[STOMP testing](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/testing.html) ·
[JEP 500](https://openjdk.org/jeps/500) · [JEP 525](https://openjdk.org/jeps/525) ·
[Byte Buddy release notes](https://raw.githubusercontent.com/raphw/byte-buddy/master/release-notes.md) ·
[JaCoCo changes](https://www.jacoco.org/jacoco/trunk/doc/changes.html) ·
[ArchUnit releases](https://github.com/TNG/ArchUnit/releases) ·
[Feathers on seams](https://www.informit.com/articles/article.aspx?p=359417&seqNum=2) ·
[Carlo, three steps](https://understandlegacycode.com/blog/3-steps-to-add-tests-on-existing-code-when-you-have-short-deadlines/)
