package org.openelisglobal.qc.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.openelisglobal.qc.valueholder.QCEntryPoint;
import org.openelisglobal.qc.valueholder.QCQualitativeOutcome;
import org.openelisglobal.validation.annotations.SafeHtml;

/**
 * One manual QC run on an analyzer, as entered from the analyzer import page or
 * the analyzers list (#3490).
 *
 * @param controlLotId  one of the analyzer's usable control lots; it also names
 *                      the test
 * @param outcome       the technician's verdict, PASS or FAIL
 * @param resultValue   optional measured value; when present against a lot with
 *                      statistics it gets a z-score and Westgard evaluation
 * @param notes         optional free text, such as the control reading
 * @param runDateTime   when the control was run; now when absent, never in the
 *                      future
 * @param performedById the system user who ran the control, for delegated
 *                      entry; the session user when absent
 * @param entryPoint    which screen recorded the run
 */
public record AnalyzerManualQcForm(@NotBlank @Size(max = 36) String controlLotId, @NotNull QCQualitativeOutcome outcome,
        BigDecimal resultValue, @Size(max = 500) @SafeHtml(level = SafeHtml.SafeListLevel.NONE) String notes,
        LocalDateTime runDateTime,
        @Pattern(regexp = "[1-9]\\d*", message = "must be a positive numeric ID") String performedById,
        @NotNull QCEntryPoint entryPoint) {
}
