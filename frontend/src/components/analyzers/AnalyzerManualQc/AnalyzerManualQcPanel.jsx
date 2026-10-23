import React, { useCallback, useEffect, useState } from "react";
import {
  Button,
  InlineLoading,
  InlineNotification,
  Link,
  Tag,
  Tile,
} from "@carbon/react";
import { useIntl } from "react-intl";
import { Link as RouterLink } from "react-router-dom";
import QcRecordForm from "./QcRecordForm";
import {
  fetchManualQcStatus,
  fromLabLocalDateTime,
  historyPath,
} from "./manualQcApi";
import "./AnalyzerManualQcPanel.css";

const STATE_TAG = {
  PASS: "green",
  FAILED: "red",
  OVERDUE: "red",
  NOT_RUN: "gray",
};

/**
 * Manual QC status for one analyzer (#3490): the last manual run, whether QC
 * is due, and an inline form to record a run. On the import page it sits
 * below the header; on the analyzers list it is the "Record Manual QC" row
 * expansion, opened with the form showing.
 *
 * Loads with one request (GET qc-status), and a save returns the re-evaluated
 * status, so the panel never refetches after recording.
 */
const AnalyzerManualQcPanel = ({
  analyzerId,
  entryPoint = "ANALYZER_IMPORT",
  startWithForm = false,
  onClose,
}) => {
  const intl = useIntl();
  const [status, setStatus] = useState(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [formOpen, setFormOpen] = useState(startWithForm);
  const [justSaved, setJustSaved] = useState(false);

  const load = useCallback(
    (signal) => {
      fetchManualQcStatus(
        analyzerId,
        (response) => {
          if (signal?.aborted) return;
          if (response && response.state) {
            setStatus(response);
          } else {
            setLoadFailed(true);
          }
        },
        signal,
      );
    },
    [analyzerId],
  );

  useEffect(() => {
    if (!analyzerId) return undefined;
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [analyzerId, load]);

  const formatDateTime = (text) => {
    const date = fromLabLocalDateTime(text);
    return date
      ? intl.formatDate(date, { dateStyle: "medium", timeStyle: "short" })
      : "";
  };

  if (loadFailed) {
    return (
      <InlineNotification
        kind="error"
        lowContrast
        hideCloseButton
        title={intl.formatMessage({ id: "analyzer.manualQc.title" })}
        subtitle={intl.formatMessage({ id: "analyzer.manualQc.loadError" })}
        data-testid="manual-qc-load-error"
      />
    );
  }
  if (!status) {
    return (
      <InlineLoading
        description={intl.formatMessage({ id: "analyzer.manualQc.loading" })}
      />
    );
  }

  const last = status.lastRun;
  const lots = status.lots || [];
  const lastQcText = last
    ? formatDateTime(last.runDateTime)
    : intl.formatMessage({ id: "analyzer.manualQc.never" });
  const canRecord = status.canRecord && lots.length > 0;

  const onSaved = (newStatus) => {
    setStatus(newStatus);
    setFormOpen(false);
    setJustSaved(true);
  };

  return (
    <Tile
      className={`manual-qc-panel manual-qc-panel--${status.state.toLowerCase()}`}
      data-testid={`manual-qc-panel-${entryPoint}`}
    >
      <div className="manual-qc-panel__header">
        <h4>{intl.formatMessage({ id: "analyzer.manualQc.title" })}</h4>
        <Tag
          type={STATE_TAG[status.state] || "gray"}
          data-testid="manual-qc-state"
        >
          {intl.formatMessage({
            id: `analyzer.manualQc.state.${status.state}`,
          })}
        </Tag>
        {onClose && (
          <Button kind="ghost" size="sm" onClick={onClose}>
            {intl.formatMessage({ id: "analyzer.manualQc.close" })}
          </Button>
        )}
      </div>

      {status.overdue && status.qcRequired && (
        <InlineNotification
          kind="warning"
          lowContrast
          hideCloseButton
          title={intl.formatMessage({ id: "analyzer.manualQc.overdue.title" })}
          subtitle={intl.formatMessage(
            { id: "analyzer.manualQc.overdue.message" },
            { lastQc: lastQcText },
          )}
          data-testid="manual-qc-overdue"
        />
      )}
      {status.state === "FAILED" && (
        <InlineNotification
          kind="error"
          lowContrast
          hideCloseButton
          title={intl.formatMessage({ id: "analyzer.manualQc.failed.title" })}
          subtitle={intl.formatMessage(
            { id: "analyzer.manualQc.failed.message" },
            { analyzer: status.analyzerName },
          )}
          data-testid="manual-qc-failed"
        />
      )}
      {justSaved && status.state !== "FAILED" && (
        <InlineNotification
          kind="success"
          lowContrast
          onClose={() => setJustSaved(false)}
          title={intl.formatMessage({ id: "analyzer.manualQc.form.saved" })}
          data-testid="manual-qc-saved"
        />
      )}

      <dl className="manual-qc-panel__summary" data-testid="manual-qc-last-run">
        <div>
          <dt>{intl.formatMessage({ id: "analyzer.manualQc.lastRun" })}</dt>
          <dd>{lastQcText}</dd>
        </div>
        {last && (
          <>
            <div>
              <dt>
                {intl.formatMessage({ id: "analyzer.manualQc.performedBy" })}
              </dt>
              <dd>{last.performedBy}</dd>
            </div>
            <div>
              <dt>{intl.formatMessage({ id: "analyzer.manualQc.result" })}</dt>
              <dd>
                {intl.formatMessage({
                  id: `analyzer.manualQc.outcome.${last.outcome}`,
                })}
              </dd>
            </div>
            {(last.resultValue != null || last.notes) && (
              <div>
                <dt>{intl.formatMessage({ id: "analyzer.manualQc.value" })}</dt>
                <dd>
                  {[
                    last.resultValue != null
                      ? `${intl.formatNumber(last.resultValue)}${last.unitOfMeasure ? ` ${last.unitOfMeasure}` : ""}`
                      : null,
                    last.notes,
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </dd>
              </div>
            )}
          </>
        )}
        {status.nextDueAt && (
          <div>
            <dt>{intl.formatMessage({ id: "analyzer.manualQc.nextDue" })}</dt>
            <dd>{formatDateTime(status.nextDueAt)}</dd>
          </div>
        )}
      </dl>

      {lots.length === 0 && (
        <p className="manual-qc-panel__hint" data-testid="manual-qc-no-lots">
          {intl.formatMessage({ id: "analyzer.manualQc.noLots" })}
        </p>
      )}

      {formOpen && canRecord ? (
        <QcRecordForm
          analyzerId={analyzerId}
          lots={lots}
          entryPoint={entryPoint}
          onSaved={onSaved}
          onCancel={() =>
            onClose && startWithForm ? onClose() : setFormOpen(false)
          }
        />
      ) : (
        <div className="manual-qc-panel__actions">
          {canRecord && (
            <Button
              size="sm"
              onClick={() => {
                setJustSaved(false);
                setFormOpen(true);
              }}
              data-testid="manual-qc-record"
            >
              {intl.formatMessage({ id: "analyzer.manualQc.recordQc" })}
            </Button>
          )}
          {status.canViewHistory && (
            <Link
              as={RouterLink}
              to={historyPath(analyzerId)}
              data-testid="manual-qc-history"
            >
              {intl.formatMessage({ id: "analyzer.manualQc.viewHistory" })}
            </Link>
          )}
        </div>
      )}
    </Tile>
  );
};

export default AnalyzerManualQcPanel;
