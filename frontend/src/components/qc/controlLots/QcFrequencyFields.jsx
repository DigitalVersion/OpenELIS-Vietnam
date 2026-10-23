import React from "react";
import {
  Column,
  Dropdown,
  FormGroup,
  NumberInput,
  Toggle,
} from "@carbon/react";
import { useIntl } from "react-intl";

export const FREQUENCY_TYPES = ["", "DAILY", "PER_SHIFT", "CUSTOM_HOURS"];

/**
 * Manual QC frequency for a control lot (#3490, Batch Workplan spec v1.1):
 * how often the lot needs a passing manual run, and whether an overdue lot
 * warns on the analyzer import page.
 */
const QcFrequencyFields = ({ values, errors, touched, setFieldValue }) => {
  const intl = useIntl();
  const typeLabel = (type) =>
    intl.formatMessage({
      id: `qc.controlLot.frequency.type.${type || "NONE"}`,
    });
  const needsHours =
    values.qcFrequencyType === "PER_SHIFT" ||
    values.qcFrequencyType === "CUSTOM_HOURS";

  return (
    <>
      <Column lg={8} md={4} sm={4}>
        <FormGroup legendText="">
          <Dropdown
            id="qc-frequency-type"
            titleText={intl.formatMessage({
              id: "qc.controlLot.frequency.type",
            })}
            label={typeLabel("")}
            items={FREQUENCY_TYPES}
            itemToString={typeLabel}
            selectedItem={values.qcFrequencyType || ""}
            onChange={({ selectedItem }) => {
              setFieldValue("qcFrequencyType", selectedItem || "");
              if (!selectedItem) {
                setFieldValue("qcRequired", false);
              }
            }}
            invalid={!!(touched.qcFrequencyType && errors.qcFrequencyType)}
            invalidText={errors.qcFrequencyType}
            data-testid="control-lot-qc-frequency-type"
          />
        </FormGroup>
      </Column>
      {needsHours && (
        <Column lg={4} md={2} sm={4}>
          <FormGroup legendText="">
            <NumberInput
              id="qc-frequency-hours"
              label={intl.formatMessage({
                id: "qc.controlLot.frequency.hours",
              })}
              helperText={intl.formatMessage({
                id: "qc.controlLot.frequency.hoursHelper",
              })}
              min={1}
              max={720}
              allowEmpty
              value={values.qcFrequencyHours ?? ""}
              onChange={(_event, { value }) =>
                setFieldValue(
                  "qcFrequencyHours",
                  value === "" || value == null ? "" : Number(value),
                )
              }
              invalid={!!(touched.qcFrequencyHours && errors.qcFrequencyHours)}
              invalidText={errors.qcFrequencyHours}
              data-testid="control-lot-qc-frequency-hours"
            />
          </FormGroup>
        </Column>
      )}
      <Column lg={4} md={2} sm={4}>
        <FormGroup legendText="">
          <Toggle
            id="qc-required"
            labelText={intl.formatMessage({
              id: "qc.controlLot.frequency.required",
            })}
            labelA={intl.formatMessage({ id: "qc.controlLot.frequency.off" })}
            labelB={intl.formatMessage({ id: "qc.controlLot.frequency.on" })}
            toggled={!!values.qcRequired}
            disabled={!values.qcFrequencyType}
            onToggle={(checked) => setFieldValue("qcRequired", checked)}
            data-testid="control-lot-qc-required"
          />
        </FormGroup>
      </Column>
    </>
  );
};

export default QcFrequencyFields;
