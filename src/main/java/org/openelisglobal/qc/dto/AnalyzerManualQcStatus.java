package org.openelisglobal.qc.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything the manual QC panel needs in one response (#3490): the analyzer's
 * QC state, its last manual run, and the control lots a new run can be recorded
 * against.
 *
 * @param state          PASS, FAILED, OVERDUE or NOT_RUN
 * @param qcRequired     whether any of the analyzer's lots enforces a
 *                       frequency; when false the panel is informational and
 *                       never warns
 * @param overdue        whether a lot that enforces a frequency is not covered
 *                       by a passing run
 * @param nextDueAt      the earliest time a covered, enforcing lot falls due
 * @param canRecord      whether the caller may record a run (UI hint only; the
 *                       API enforces it)
 * @param canViewHistory whether the caller may open the full QC history
 */
public record AnalyzerManualQcStatus(String analyzerId, String analyzerName, State state, boolean qcRequired,
        boolean overdue, Run lastRun, @JsonFormat(pattern = ISO) LocalDateTime nextDueAt, List<Lot> lots,
        @JsonFormat(pattern = ISO) LocalDateTime evaluatedAt, boolean canRecord, boolean canViewHistory) {

    /** Lab-local date-times, as the analyzer import page shows them. */
    public static final String ISO = "yyyy-MM-dd'T'HH:mm:ss";

    public enum State {
        PASS, FAILED, OVERDUE, NOT_RUN
    }

    /** One manual QC run as the panel and the history list show it. */
    public record Run(String id, @JsonFormat(pattern = ISO) LocalDateTime runDateTime, String performedBy,
            String outcome, String resultStatus, BigDecimal resultValue, String unitOfMeasure, String notes,
            String controlLotId, String controlLotLabel, String testName, String entryPoint) {
    }

    /** A control lot of the analyzer that can take a new run. */
    public record Lot(String id, String label, String lotNumber, String controlLevel, String testId, String testName,
            String status, String frequencyType, Integer frequencyHours, boolean qcRequired,
            @JsonFormat(pattern = ISO) LocalDateTime lastPassAt, @JsonFormat(pattern = ISO) LocalDateTime dueAt,
            boolean overdue) {
    }

    public AnalyzerManualQcStatus withPermissions(boolean record, boolean viewHistory) {
        return new AnalyzerManualQcStatus(analyzerId, analyzerName, state, qcRequired, overdue, lastRun, nextDueAt,
                lots, evaluatedAt, record, viewHistory);
    }
}
