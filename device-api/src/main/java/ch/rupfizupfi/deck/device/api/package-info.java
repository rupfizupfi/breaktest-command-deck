/**
 * The hardware contract between the deck and its driver plugins. The deck consumes these types;
 * the driver repos ({@code dscusb}, {@code usbmodbus}) implement them and are rebuilt against this
 * build's live source, so a change here surfaces as a compile error over there, never at runtime.
 *
 * <h2>Evolution policy</h2>
 *
 * The driver repos are <em>providers</em> of these interfaces, so compatibility is judged
 * provider-side, not caller-side:
 *
 * <ul>
 * <li>Adding an interface method breaks providers unless it is a {@code default} method with a
 *     safe fallback, such as {@link ch.rupfizupfi.deck.device.api.Drive#readControlSource} (1.3.0)
 *     answering "unknown". Prefer that; otherwise rebuild both driver jars in the same change. When no
 *     behaviour is safe to fake, the fallback throws {@link UnsupportedOperationException} and the
 *     deck refuses whatever needed the method: an older plugin still loads, and fails closed at the
 *     call. {@link ch.rupfizupfi.deck.device.api.Drive#enableLinkLossStop} (1.2.0) is one.</li>
 * <li>Record components cannot be added compatibly: new data means a new type.
 *     {@link ch.rupfizupfi.deck.device.api.Measurement}'s JSON keys are also wire contract on
 *     {@code /topic/load-cell}.</li>
 * <li>The version in this build's {@code build.gradle} is semver against providers: a default
 *     method bumps minor, anything a provider must implement bumps major. Bump on contract change,
 *     never per app release.</li>
 * <li>{@link ch.rupfizupfi.deck.device.api.DeviceApi#verifyPluginBuiltAgainst} enforces that
 *     version at runtime against file-dropped jars, so a forgotten bump disarms the check. The deck
 *     refuses a provider whose plugin never called it, and warns when the version it reported
 *     predates a {@code default} the deck relies on, so a new {@code default} with a fallback that
 *     hides something from the operator owes a row in the deck's {@code ContractFeature}.</li>
 * <li>A new type is additive and bumps minor, but a plugin that names it cannot load on an older
 *     deck; the skew check's "newer minor" refusal is what keeps it off one.
 *     {@link ch.rupfizupfi.deck.device.api.DriveCommunicationException} (1.4.0) is one.</li>
 * <li>A capability a provider declares is how a default can tell old from new.
 *     {@link ch.rupfizupfi.deck.device.api.Drive#gating()} (1.5.0) answers
 *     {@link ch.rupfizupfi.deck.device.api.DriveGating#CALLER_SERIALIZES} for every older plugin, so
 *     the deck, which serializes no drive call, carries only stops and reads through that plugin and
 *     refuses what needs the driver's gate (energize), while the defaults that need the gate
 *     ({@code stopEpoch}, {@code runLinked}, {@code async}) throw rather than run unfenced. The
 *     caller-serializes fallback hides a slower stop, so it has its {@code ContractFeature} row. The
 *     capability and those defaults go in the next major, once every provider answers
 *     {@link ch.rupfizupfi.deck.device.api.DriveGating#DRIVER_SERIALIZES}.</li>
 * <li>The {@code support} package is shared implementation a provider may use or not, but its
 *     public and protected signatures ({@code DriveOperations}, {@code GatedDrive}'s constructor and
 *     overridable methods, {@code LaneDeadlines}, {@code TransactionScheduler}'s public API) are
 *     binary contract for a provider built on it: a driver keeps device-api {@code compileOnly} and
 *     links the deck's copy at runtime, and the skew check compares only the contract version. A
 *     signature change there follows the rules above (a {@code default} added to
 *     {@code DriveOperations} is minor, an abstract one major); only an implementation change that
 *     keeps every documented behaviour bumps nothing.</li>
 * <li>Behaviour is contract too. The {@code java-test-fixtures} of this build (1.4.0) carry
 *     {@code DriveContractTest} and {@code LoadCellStreamContractTest}; every provider extends them,
 *     so a change to documented behaviour lands as a change to those tests, in the same bump. A case
 *     that holds only for a capability (1.5.0's concurrency cases, for a gated drive) skips for a
 *     provider that does not declare it. The fixture SPI follows the interface rule: a method added
 *     in a minor is a {@code default} (1.5.0's {@code commandedRpm} and {@code transactionLatency},
 *     1.7.0's {@code lastRequestNanos} throw), so an older driver's fixture keeps compiling. An
 *     obligation that follows from a capability is held by such a case, not declared as a capability
 *     of its own: a {@link ch.rupfizupfi.deck.device.api.DriveGating#DRIVER_SERIALIZES} provider
 *     feeds the link watchdog it armed, because the deck sends it no keep-alive (1.7.0).</li>
 * </ul>
 */
package ch.rupfizupfi.deck.device.api;
