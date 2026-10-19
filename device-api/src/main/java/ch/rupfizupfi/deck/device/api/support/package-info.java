/**
 * The call gate a {@link ch.rupfizupfi.deck.device.api.DriveGating#DRIVER_SERIALIZES} provider
 * builds on: {@link ch.rupfizupfi.deck.device.api.support.TransactionScheduler} (lanes, stop first,
 * deadlines, stop epoch, linked groups) and
 * {@link ch.rupfizupfi.deck.device.api.support.GatedDrive}, the {@code Drive} over a provider's
 * {@link ch.rupfizupfi.deck.device.api.support.DriveOperations}. Since contract 1.5.0.
 * <p>
 * Here rather than in each provider so every gated provider runs one implementation with one test
 * suite: the simulator today, and the CFW11 driver once it gates its own calls (BL-116/BL-117), so
 * the simulated bench exercises the gate a gated vendor driver will run. JDK only,
 * like the rest of device-api: no logging, no dependency. A driver keeps device-api
 * {@code compileOnly}, so the copy it runs is the deck's: a fix here ships with the deck, not with
 * a jar refresh. For the same reason its public and protected signatures are binary contract and
 * follow the api package's minor/major rules; only an implementation change bumps nothing.
 */
package ch.rupfizupfi.deck.device.api.support;
