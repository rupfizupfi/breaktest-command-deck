package ch.rupfizupfi.deck.device.frequencyconverter;

import java.io.Serializable;

public class Info implements Serializable {
    private static final long serialVersionUID = 1L;

    double speed;
    boolean start;
    boolean generalEnable;
    boolean useSecondRamp;
    boolean directionIsForward;
}