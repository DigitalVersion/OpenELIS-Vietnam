package org.openelisglobal.qc.service;

import java.util.List;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus;
import org.openelisglobal.qc.form.AnalyzerManualQcForm;

/**
 * Manual QC on an analyzer (#3490): its status, its history, and recording a
 * new run. Reads and writes the shared {@code qc_result} store; there is no
 * separate manual QC table.
 */
public interface AnalyzerManualQcService {

    /**
     * The analyzer's manual QC status, last run and usable control lots, in one
     * call so the panel loads with a single request.
     *
     * @throws org.hibernate.ObjectNotFoundException if there is no such analyzer
     */
    AnalyzerManualQcStatus getStatus(String analyzerId);

    /** The analyzer's manual QC runs, newest first. */
    List<AnalyzerManualQcStatus.Run> getHistory(String analyzerId, int maxRows);

    /**
     * Record a run and return the run as saved.
     *
     * @throws IllegalArgumentException if the run is invalid (see
     *                                  {@link QCResultService#createAnalyzerManualQCResult})
     *                                  or the performer is not a system user
     */
    AnalyzerManualQcStatus.Run record(String analyzerId, AnalyzerManualQcForm run, int recorderUserId);
}
