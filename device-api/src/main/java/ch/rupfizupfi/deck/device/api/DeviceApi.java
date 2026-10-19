package ch.rupfizupfi.deck.device.api;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards a driver plugin against the contract it is actually running on.
 *
 * <p>Drivers are file-dropped onto {@code loader.path}, so the jar on the machine may have been
 * built against an older or newer contract than the one deployed.
 *
 * <p>Usage — pass {@link ContractVersion#VALUE} literally, from the plugin:
 *
 * <pre>{@code DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE);}</pre>
 *
 * <p>This does not compare a value to itself: {@code VALUE} is a compile-time constant, so javac
 * inlines it into the plugin's class file and the argument is the version the plugin was built
 * against, while this method reads the one on the classpath. Replacing the argument with a runtime
 * lookup would always pass.
 *
 * <p>Compatibility follows the evolution policy in this package's {@code package-info}, judged
 * provider-side:
 *
 * <ul>
 * <li>Different major: refused.</li>
 * <li>Plugin built against a newer minor: refused, it may hit a {@code NoSuchMethodError}
 *     mid-run.</li>
 * <li>Plugin built against an older minor: allowed, default methods fill the gap.</li>
 * </ul>
 *
 * <p>A passing call is recorded under the calling class ({@link #reportedBuiltAgainst()}), so the
 * deck can refuse a provider whose plugin never made it. Call it from the auto-configuration whose
 * {@code @Bean} method returns the provider, or from the provider class itself; a call routed
 * through any other class does not vouch for the provider.
 */
public final class DeviceApi {

    private static final StackWalker CALLER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final Map<Class<?>, String> REPORTED = new ConcurrentHashMap<>();

    private DeviceApi() {
    }

    /**
     * Every class that passed {@link #verifyPluginBuiltAgainst}, with the version it was built
     * against. An unmodifiable live view; entries are never removed.
     */
    public static Map<Class<?>, String> reportedBuiltAgainst() {
        return Collections.unmodifiableMap(REPORTED);
    }

    /**
     * @param builtAgainst always {@link ContractVersion#VALUE}, inlined at the caller's compile time
     * @throws IllegalStateException if the plugin cannot safely run against this contract
     */
    public static void verifyPluginBuiltAgainst(String builtAgainst) {
        // Must stay in this method: getCallerClass() answers the caller of the method it runs in.
        Class<?> caller = CALLER.getCallerClass();
        check(builtAgainst);
        REPORTED.put(caller, builtAgainst);
    }

    private static void check(String builtAgainst) {
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
                + "doc/dev/backend/driver-jars.md.");
    }

    private static int major(String version) {
        return part(version, 0);
    }

    private static int minor(String version) {
        return part(version, 1);
    }

    /** Lenient: an unparseable version must not stop the bench. */
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
