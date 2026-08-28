package ch.rupfizupfi.deck.device.api;

/**
 * Guards a driver plugin against the contract it is actually running on.
 *
 * <p>Drivers are dropped in as jars and loaded from {@code loader.path}, so a plugin built months
 * ago can meet a newer contract with nothing in between to notice. Compile-time checks cannot see
 * that: the driver repos compile against the live contract, but the <em>jar on the machine</em> is
 * whatever someone last copied there.
 *
 * <p>Usage — pass {@link ContractVersion#VALUE} literally, from the plugin:
 *
 * <pre>{@code DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE);}</pre>
 *
 * <p>That call looks like it compares a value to itself, and it is load-bearing that it does not.
 * {@code VALUE} is a compile-time constant, so javac <b>inlines it into the plugin's own class
 * file</b>: the argument is the version the plugin was <em>built</em> against, while this method
 * reads the version actually on the classpath. Do not "simplify" the argument away, and do not
 * replace it with a runtime lookup — that would compare the deployed contract to itself and always
 * pass.
 *
 * <p>Compatibility follows the evolution policy in this package's {@code package-info}, judged
 * provider-side:
 *
 * <ul>
 * <li><b>Different major</b> — something a provider must implement changed. Refused.</li>
 * <li><b>Plugin built against a newer minor</b> — it may call a default method this contract does
 *     not have yet, which would surface as a {@code NoSuchMethodError} mid-run. Refused.</li>
 * <li><b>Plugin built against an older minor</b> — default methods fill the gap. Allowed, silently;
 *     that is exactly what minor bumps promise.</li>
 * </ul>
 */
public final class DeviceApi {

    private DeviceApi() {
    }

    /**
     * @param builtAgainst always {@link ContractVersion#VALUE}, inlined at the caller's compile time
     * @throws IllegalStateException if the plugin cannot safely run against this contract
     */
    public static void verifyPluginBuiltAgainst(String builtAgainst) {
        String running = ContractVersion.VALUE;
        if (running.equals(builtAgainst)) {
            return;
        }

        int builtMajor = major(builtAgainst);
        int runningMajor = major(running);
        if (builtMajor != runningMajor) {
            throw incompatible(builtAgainst, running,
                    "Major versions differ, so a method this plugin must implement has changed.");
        }
        if (minor(builtAgainst) > minor(running)) {
            throw incompatible(builtAgainst, running,
                    "The plugin was built against a newer minor and may call a contract method "
                            + "this version does not have.");
        }
    }

    private static IllegalStateException incompatible(String builtAgainst, String running,
                                                      String why) {
        return new IllegalStateException("Driver plugin was built against device-api "
                + builtAgainst + " but is running against " + running + ". " + why
                + " Rebuild the driver against this contract and replace its jar - it is a file "
                + "drop on loader.path, so no application rebuild is needed. See "
                + "doc/03-backend/driver-jars.md.");
    }

    private static int major(String version) {
        return part(version, 0);
    }

    private static int minor(String version) {
        return part(version, 1);
    }

    /** Lenient on purpose: an unparseable version must not be the thing that stops the bench. */
    private static int part(String version, int index) {
        String[] parts = version.split("[.-]");
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
