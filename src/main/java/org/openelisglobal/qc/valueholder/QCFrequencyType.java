package org.openelisglobal.qc.valueholder;

/**
 * How often a control lot must have a passing manual run (#3490). Set per
 * control lot, as in the Batch Workplan spec v1.1, because a lot is already
 * scoped to one (test, instrument) pair.
 */
public enum QCFrequencyType {

    /** A passing run on the current calendar day, lab-local time. */
    DAILY,

    /**
     * A passing run within the current shift, a rolling window of
     * {@code qcFrequencyHours} hours (8 when unset).
     */
    PER_SHIFT,

    /** A passing run within the last {@code qcFrequencyHours} hours. */
    CUSTOM_HOURS;

    /** Shift length used when a PER_SHIFT lot does not set its own. */
    public static final int DEFAULT_SHIFT_HOURS = 8;
}
