package ch.rupfizupfi.deck.device.api.support;

import ch.rupfizupfi.deck.device.api.DriveLane;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a call in each {@link DriveLane} may take from submission to reply. The STOP lane's is
 * the contract's {@link DriveLane#STOP_DEADLINE_MILLIS}; the others are the provider's, derived from
 * its transport's timeouts and a lane's transaction count.
 */
public record LaneDeadlines(Duration stop, Duration control, Duration setpoint, Duration config,
                            Duration status) {

    public LaneDeadlines {
        for (Duration d : new Duration[]{stop, control, setpoint, config, status}) {
            Objects.requireNonNull(d, "every lane needs a deadline");
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("a lane deadline must be positive, got " + d);
            }
        }
    }

    /** The provider's deadlines for the four lanes it owns, with the contract's STOP deadline. */
    public static LaneDeadlines of(Duration control, Duration setpoint, Duration config, Duration status) {
        return new LaneDeadlines(Duration.ofMillis(DriveLane.STOP_DEADLINE_MILLIS), control, setpoint,
                config, status);
    }

    public Duration of(DriveLane lane) {
        return switch (lane) {
            case STOP -> stop;
            case CONTROL -> control;
            case SETPOINT -> setpoint;
            case CONFIG -> config;
            case STATUS -> status;
        };
    }
}
