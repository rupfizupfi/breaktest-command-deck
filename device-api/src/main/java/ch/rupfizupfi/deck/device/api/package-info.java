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
 * <li>Adding an interface method is <b>breaking for providers</b> unless it is a {@code default}
 *     method with a safe fallback — the post-Java-8 JDBC approach. Prefer that; when a default
 *     cannot be safe, rebuild both driver jars in the same change and say so in the commit.</li>
 * <li>Record components cannot be added compatibly at all: new data means a new type. And
 *     {@link ch.rupfizupfi.deck.device.api.Measurement}'s JSON keys are wire contract on
 *     {@code /topic/load-cell} — renaming a component silently breaks the untyped frontend
 *     consumer that no typecheck sees.</li>
 * <li>The version in this build's {@code build.gradle} is semver <b>against providers</b>:
 *     default-method addition bumps minor, anything a provider must implement bumps major. Bump on
 *     contract change, never per app release.</li>
 * </ul>
 */
package ch.rupfizupfi.deck.device.api;
