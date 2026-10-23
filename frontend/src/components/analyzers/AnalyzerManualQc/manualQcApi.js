import {
  getFromOpenElisServer,
  postToOpenElisServerJsonResponse,
} from "../../utils/Utils";

const base = (analyzerId) =>
  `/rest/analyzer/analyzers/${encodeURIComponent(analyzerId)}`;

/** The panel's single request: state, last run, lots and permission hints. */
export const fetchManualQcStatus = (analyzerId, callback, signal) =>
  getFromOpenElisServer(`${base(analyzerId)}/qc-status`, callback, signal);

/** Record a run; the 201 body is { run, status }. */
export const recordManualQc = (analyzerId, run, callback) =>
  postToOpenElisServerJsonResponse(
    `${base(analyzerId)}/qc-runs`,
    JSON.stringify(run),
    callback,
  );

export const fetchUsers = (callback) =>
  getFromOpenElisServer("/rest/users", callback);

/** The analyzer's QC history page in the QC module. */
export const historyPath = (analyzerId) =>
  `/analyzers/qc/instruments/${encodeURIComponent(analyzerId)}`;

/** "2026-10-23T09:05:00" for a lab wall-clock Date, as the server reads it. */
export const toLabLocalDateTime = (date) => {
  const pad = (n) => String(n).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
  );
};

/** Parse a server lab-local "yyyy-MM-ddTHH:mm:ss" into a wall-clock Date. */
export const fromLabLocalDateTime = (text) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/.exec(
    text || "",
  );
  if (!m) return null;
  return new Date(
    Number(m[1]),
    Number(m[2]) - 1,
    Number(m[3]),
    Number(m[4]),
    Number(m[5]),
    Number(m[6] || 0),
  );
};
