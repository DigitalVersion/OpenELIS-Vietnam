package org.openelisglobal.qc.service;

import java.time.LocalDateTime;
import org.openelisglobal.qc.valueholder.QCFrequencyType;

/**
 * When a control lot's next passing manual run is due (#3490). The rules follow
 * the Batch Workplan spec (BR-002): DAILY means a pass on the current calendar
 * day, PER_SHIFT a pass inside a rolling shift window, CUSTOM_HOURS a pass
 * inside the configured number of hours. Pure, so it is unit tested without a
 * clock or a database.
 */
public final class ManualQcFrequencyEvaluator {

    private ManualQcFrequencyEvaluator() {
    }

    /**
     * The moment the lot stops being covered by its last passing run.
     *
     * @return null when the lot has no frequency rule or has never passed
     */
    public static LocalDateTime dueAt(QCFrequencyType type, Integer hours, LocalDateTime lastPass) {
        if (type == null || lastPass == null) {
            return null;
        }
        return switch (type) {
        case DAILY -> lastPass.toLocalDate().plusDays(1).atStartOfDay();
        case PER_SHIFT -> lastPass.plusHours(hours != null ? hours : QCFrequencyType.DEFAULT_SHIFT_HOURS);
        // The form refuses CUSTOM_HOURS without hours; a lot saved before that check
        // is treated as never covered rather than covered forever.
        case CUSTOM_HOURS -> hours != null ? lastPass.plusHours(hours) : lastPass;
        };
    }

    /**
     * Whether a lot with a frequency rule is overdue at {@code now}. A lot with a
     * rule that has never passed is overdue; a lot with no rule never is.
     */
    public static boolean isOverdue(QCFrequencyType type, Integer hours, LocalDateTime lastPass, LocalDateTime now) {
        if (type == null) {
            return false;
        }
        LocalDateTime due = dueAt(type, hours, lastPass);
        return due == null || !now.isBefore(due);
    }
}
