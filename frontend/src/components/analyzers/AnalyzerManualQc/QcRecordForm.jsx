import React, { useContext, useEffect, useMemo, useState } from "react";
import {
  Button,
  ComboBox,
  Dropdown,
  Form,
  InlineNotification,
  RadioButton,
  RadioButtonGroup,
  Stack,
  TextArea,
  TextInput,
} from "@carbon/react";
import { useIntl } from "react-intl";
import CustomDatePicker from "../../common/CustomDatePicker";
import CustomTimePicker from "../../common/CustomTimePicker";
import { ConfigurationContext } from "../../layout/Layout";
import UserSessionDetailsContext from "../../../UserSessionDetailsContext";
import { labNow } from "../../utils/labClock";
import { displayDateToIso } from "../../utils/Utils";
import { fetchUsers, recordManualQc, toLabLocalDateTime } from "./manualQcApi";

const NOTES_MAX = 500;
const pad = (n) => String(n).padStart(2, "0");

const toDisplayDate = (date, dateLocale) =>
  dateLocale === "fr-FR"
    ? `${pad(date.getDate())}/${pad(date.getMonth() + 1)}/${date.getFullYear()}`
    : `${pad(date.getMonth() + 1)}/${pad(date.getDate())}/${date.getFullYear()}`;

/**
 * Check a run before it is sent. Returns { field: messageId } for each problem;
 * the server repeats every check, this only spares the technician a round trip.
 */
export const validateRun = (values, now, dateLocale) => {
  const errors = {};
  if (!values.controlLotId) {
    errors.controlLotId = "analyzer.manualQc.form.controlLotRequired";
  }
  if (values.outcome !== "PASS" && values.outcome !== "FAIL") {
    errors.outcome = "error.analyzerQc.qcRequired";
  }
  const iso = displayDateToIso(values.date, dateLocale);
  if (!iso || !/^\d{2}:\d{2}$/.test(values.time || "")) {
    errors.date = "analyzer.manualQc.form.dateRequired";
  } else if (`${iso}T${values.time}:00` > toLabLocalDateTime(now)) {
    errors.date = "error.analyzerQc.qcDateFuture";
  }
  if (values.value && !/^-?\d+(\.\d+)?$/.test(values.value.trim())) {
    errors.value = "analyzer.manualQc.form.valueInvalid";
  }
  if (!values.performedById) {
    errors.performedById = "error.analyzerQc.performedByRequired";
  }
  return errors;
};

/**
 * Inline form for one manual QC run on an analyzer (#3490). Shared by the
 * analyzer import page panel and the analyzers list row, which differ only in
 * the entry point they record.
 */
const QcRecordForm = ({ analyzerId, lots, entryPoint, onSaved, onCancel }) => {
  const intl = useIntl();
  const { configurationProperties } = useContext(ConfigurationContext) || {};
  const { userSessionDetails } = useContext(UserSessionDetailsContext) || {};
  const dateLocale = configurationProperties?.DEFAULT_DATE_LOCALE;
  const [now] = useState(() => labNow());
  const [users, setUsers] = useState([]);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState(null);
  const [submitted, setSubmitted] = useState(false);
  const [values, setValues] = useState(() => ({
    controlLotId: lots.length === 1 ? lots[0].id : "",
    outcome: "",
    value: "",
    notes: "",
    date: toDisplayDate(now, dateLocale),
    time: `${pad(now.getHours())}:${pad(now.getMinutes())}`,
    performedById: userSessionDetails?.userId
      ? String(userSessionDetails.userId)
      : "",
  }));

  useEffect(() => {
    fetchUsers((list) => setUsers(Array.isArray(list) ? list : []));
  }, []);

  const errors = useMemo(
    () => validateRun(values, labNow(), dateLocale),
    [values, dateLocale],
  );
  const shown = (field) =>
    submitted && errors[field]
      ? intl.formatMessage({ id: errors[field] })
      : undefined;
  const set = (field) => (value) =>
    setValues((current) => ({ ...current, [field]: value }));

  const lotLabel = (lot) =>
    lot
      ? intl.formatMessage(
          { id: "analyzer.manualQc.form.lotLabel" },
          {
            product: lot.label,
            level: lot.controlLevel,
            test: lot.testName,
            lot: lot.lotNumber,
          },
        )
      : "";
  const selectedLot = lots.find((lot) => lot.id === values.controlLotId);
  const selectedUser =
    users.find((u) => String(u.id) === values.performedById) || null;

  const submit = (event) => {
    event.preventDefault();
    setSubmitted(true);
    setSaveError(null);
    if (Object.keys(errors).length > 0) {
      return;
    }
    setSaving(true);
    recordManualQc(
      analyzerId,
      {
        controlLotId: values.controlLotId,
        outcome: values.outcome,
        resultValue: values.value.trim() === "" ? null : values.value.trim(),
        notes: values.notes.trim() === "" ? null : values.notes.trim(),
        runDateTime: `${displayDateToIso(values.date, dateLocale)}T${values.time}:00`,
        performedById: values.performedById,
        entryPoint,
      },
      (response) => {
        setSaving(false);
        if (response && response.run && response.status) {
          onSaved(response.status, response.run);
          return;
        }
        setSaveError(
          intl.formatMessage(
            { id: "analyzer.manualQc.form.saveError" },
            { message: response?.message || "" },
          ),
        );
      },
    );
  };

  return (
    <Form onSubmit={submit} data-testid="manual-qc-form" noValidate>
      <Stack gap={5}>
        {lots.length > 1 ? (
          <Dropdown
            id={`manual-qc-lot-${entryPoint}`}
            titleText={intl.formatMessage({
              id: "analyzer.manualQc.form.controlLot",
            })}
            label={intl.formatMessage({
              id: "analyzer.manualQc.form.selectControlLot",
            })}
            items={lots}
            itemToString={lotLabel}
            selectedItem={selectedLot || null}
            onChange={({ selectedItem }) =>
              set("controlLotId")(selectedItem ? selectedItem.id : "")
            }
            invalid={!!shown("controlLotId")}
            invalidText={shown("controlLotId")}
            data-testid="manual-qc-lot"
          />
        ) : (
          <p data-testid="manual-qc-lot-single">
            {intl.formatMessage({ id: "analyzer.manualQc.form.controlLot" })}
            {": "}
            {lotLabel(lots[0])}
          </p>
        )}
        <div className="manual-qc-form-row">
          <CustomDatePicker
            id={`manual-qc-date-${entryPoint}`}
            labelText={intl.formatMessage({
              id: "analyzer.manualQc.form.date",
            })}
            value={values.date}
            updateStateValue
            disallowFutureDate
            futureDateText={intl.formatMessage({
              id: "error.analyzerQc.qcDateFuture",
            })}
            onChange={set("date")}
            invalid={!!shown("date")}
            invalidText={shown("date")}
          />
          <CustomTimePicker
            id={`manual-qc-time-${entryPoint}`}
            labelText={intl.formatMessage({
              id: "analyzer.manualQc.form.time",
            })}
            value={values.time}
            onChange={set("time")}
          />
        </div>
        <RadioButtonGroup
          legendText={intl.formatMessage({
            id: "analyzer.manualQc.form.result",
          })}
          name={`manual-qc-outcome-${entryPoint}`}
          valueSelected={values.outcome}
          onChange={(value) => set("outcome")(value)}
          invalid={!!shown("outcome")}
          invalidText={shown("outcome")}
        >
          <RadioButton
            id={`manual-qc-pass-${entryPoint}`}
            labelText={intl.formatMessage({
              id: "analyzer.manualQc.outcome.PASS",
            })}
            value="PASS"
          />
          <RadioButton
            id={`manual-qc-fail-${entryPoint}`}
            labelText={intl.formatMessage({
              id: "analyzer.manualQc.outcome.FAIL",
            })}
            value="FAIL"
          />
        </RadioButtonGroup>
        <TextInput
          id={`manual-qc-value-${entryPoint}`}
          labelText={intl.formatMessage({ id: "analyzer.manualQc.form.value" })}
          helperText={intl.formatMessage({
            id: "analyzer.manualQc.form.valueHelper",
          })}
          value={values.value}
          onChange={(e) => set("value")(e.target.value)}
          invalid={!!shown("value")}
          invalidText={shown("value")}
          inputMode="decimal"
        />
        <TextArea
          id={`manual-qc-notes-${entryPoint}`}
          labelText={intl.formatMessage({ id: "analyzer.manualQc.form.notes" })}
          value={values.notes}
          maxCount={NOTES_MAX}
          enableCounter
          rows={2}
          onChange={(e) => set("notes")(e.target.value.slice(0, NOTES_MAX))}
        />
        <ComboBox
          id={`manual-qc-performed-by-${entryPoint}`}
          titleText={intl.formatMessage({
            id: "analyzer.manualQc.form.performedBy",
          })}
          items={users}
          itemToString={(u) => (u ? u.value : "")}
          selectedItem={selectedUser}
          onChange={({ selectedItem }) =>
            set("performedById")(selectedItem ? String(selectedItem.id) : "")
          }
          invalid={!!shown("performedById")}
          invalidText={shown("performedById")}
        />
        {saveError && (
          <InlineNotification
            kind="error"
            lowContrast
            hideCloseButton
            title={intl.formatMessage({
              id: "analyzer.manualQc.form.saveErrorTitle",
            })}
            subtitle={saveError}
          />
        )}
        <div className="manual-qc-form-actions">
          <Button type="submit" disabled={saving} data-testid="manual-qc-save">
            {intl.formatMessage({ id: "analyzer.manualQc.form.save" })}
          </Button>
          <Button kind="secondary" onClick={onCancel} disabled={saving}>
            {intl.formatMessage({ id: "analyzer.manualQc.form.cancel" })}
          </Button>
        </div>
      </Stack>
    </Form>
  );
};

export default QcRecordForm;
