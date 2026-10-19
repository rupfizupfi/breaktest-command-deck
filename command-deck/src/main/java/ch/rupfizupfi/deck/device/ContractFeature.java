package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;

import java.util.Arrays;
import java.util.List;

/**
 * Contract {@code default} methods whose fallback hides something from the operator, keyed by the
 * provider type that supplies them and the contract version that added them.
 * <p>
 * A plugin built against an older minor passes the skew check by design and runs on the fallback;
 * {@link PluginContractCheck} warns at startup for every row it predates. A fallback that throws
 * needs no row: it fails closed at the call.
 */
enum ContractFeature {

    DROPPED_SAMPLE_COUNT(LoadCellStreamProvider.class, 1, 1, "LoadCellStream#droppedSampleCount()",
            "dropped samples are not reported: the fallback answers 0, so a gap the driver absorbed "
                    + "leaves no trace in the run's status"),

    READ_CONTROL_SOURCE(DriveProvider.class, 1, 3, "Drive#readControlSource()",
            "the control source always reads UNKNOWN, so every boot de-energizes the drive even under "
                    + "keypad (LOCAL) control"),

    DRIVER_GATING(DriveProvider.class, 1, 5, "Drive#gating()",
            "the driver does not gate its own calls (CALLER_SERIALIZES) and the deck no longer "
                    + "serializes them, so the deck refuses to energize through this plugin: it can stop "
                    + "and de-energize the drive, but never run a test");

    private final Class<?> providerType;
    private final int major;
    private final int minor;
    private final String method;
    private final String consequence;

    ContractFeature(Class<?> providerType, int major, int minor, String method, String consequence) {
        this.providerType = providerType;
        this.major = major;
        this.minor = minor;
        this.method = method;
        this.consequence = consequence;
    }

    /** The features a provider of {@code providerType} built against {@code builtAgainst} lacks. */
    static List<ContractFeature> missingFrom(Class<?> providerType, String builtAgainst) {
        int builtMajor = part(builtAgainst, 0);
        int builtMinor = part(builtAgainst, 1);
        return Arrays.stream(values())
                .filter(feature -> feature.providerType == providerType)
                .filter(feature -> builtMajor < feature.major
                        || (builtMajor == feature.major && builtMinor < feature.minor))
                .toList();
    }

    String since() {
        return major + "." + minor + ".0";
    }

    String method() {
        return method;
    }

    String consequence() {
        return consequence;
    }

    /** Lenient like {@code DeviceApi}'s parser: an unparseable part reads as 0. */
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
