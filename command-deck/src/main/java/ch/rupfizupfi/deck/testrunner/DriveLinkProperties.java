package ch.rupfizupfi.deck.testrunner;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The drive's own link-loss stop, under {@code deck.drive}. The default lives here, not in
 * {@code application*.properties}.
 * <p>
 * {@link MotorSafetyController#energize} arms it on every energize, and the gated driver that armed
 * it feeds it from its own traffic; see {@code doc/dev/backend/safety-and-recovery.md}.
 */
@Component
@ConfigurationProperties("deck.drive")
@Validated
public class DriveLinkProperties {

    static final Duration MIN_LINK_LOSS_TIMEOUT = Duration.ofSeconds(1);
    static final Duration MAX_LINK_LOSS_TIMEOUT = Duration.ofSeconds(10);

    /**
     * How long the drive may hear nothing from the deck before it removes general enable by itself.
     * The floor leaves the driver's keep-alive, which reads once a third of this passes without
     * traffic, room for a transaction stuck at its reply timeout before the drive gives up. The
     * ceiling bounds how long a motor runs after the PC went silent.
     */
    @NotNull
    private Duration linkLossTimeout = Duration.ofSeconds(2);

    @AssertTrue(message = "deck.drive.link-loss-timeout must be between 1s and 10s")
    public boolean isLinkLossTimeoutInRange() {
        return linkLossTimeout == null || (linkLossTimeout.compareTo(MIN_LINK_LOSS_TIMEOUT) >= 0
                && linkLossTimeout.compareTo(MAX_LINK_LOSS_TIMEOUT) <= 0);
    }

    public Duration getLinkLossTimeout() {
        return linkLossTimeout;
    }

    public void setLinkLossTimeout(Duration linkLossTimeout) {
        this.linkLossTimeout = linkLossTimeout;
    }

    /**
     * The validated timeout. Bean validation guards a Spring-bound instance; this guards one built
     * by hand, so an out-of-range value can never reach the drive.
     *
     * @throws IllegalStateException if the timeout is unset or out of range
     */
    Duration requireLinkLossTimeout() {
        if (linkLossTimeout == null || !isLinkLossTimeoutInRange()) {
            throw new IllegalStateException("deck.drive.link-loss-timeout must be between "
                    + MIN_LINK_LOSS_TIMEOUT.toSeconds() + "s and " + MAX_LINK_LOSS_TIMEOUT.toSeconds()
                    + "s, got " + linkLossTimeout);
        }
        return linkLossTimeout;
    }
}
