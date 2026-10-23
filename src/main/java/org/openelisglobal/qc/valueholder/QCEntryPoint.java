package org.openelisglobal.qc.valueholder;

/**
 * Where in the UI a manual analyzer QC run was recorded (#3490). Null on every
 * other QC row: analyzer-transmitted results and bench captures have no entry
 * point.
 */
public enum QCEntryPoint {

    /** The QC status panel on the per-analyzer results import page. */
    ANALYZER_IMPORT,

    /** The "Record Manual QC" row expansion on the analyzers list. */
    ANALYZER_LIST
}
