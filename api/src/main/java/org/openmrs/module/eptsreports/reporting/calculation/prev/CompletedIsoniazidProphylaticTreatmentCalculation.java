/*
 * The contents of this file are subject to the OpenMRS Public License Version
 * 1.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * Copyright (C) OpenMRS, LLC. All Rights Reserved.
 */
package org.openmrs.module.eptsreports.reporting.calculation.prev;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.joda.time.Days;
import org.joda.time.Interval;
import org.openmrs.EncounterType;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.calculation.patient.PatientCalculationContext;
import org.openmrs.calculation.result.CalculationResultMap;
import org.openmrs.calculation.result.ListResult;
import org.openmrs.module.eptsreports.metadata.HivMetadata;
import org.openmrs.module.eptsreports.reporting.calculation.AbstractPatientCalculation;
import org.openmrs.module.eptsreports.reporting.calculation.BooleanResult;
import org.openmrs.module.eptsreports.reporting.calculation.common.EPTSCalculationService;
import org.openmrs.module.eptsreports.reporting.utils.EptsCalculationUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Patients that completed isoniazid prophylactic treatment
 *
 * @return a CulculationResultMap
 */
@Component
public class CompletedIsoniazidProphylaticTreatmentCalculation extends AbstractPatientCalculation {

  private static final int NUMBER_ISONIAZID_USAGE_TO_CONSIDER_COMPLETED = 6;

  private static final int MONTHS_TO_CHECK_FOR_ISONIAZID_USAGE = 7;

  private static final int MINIMUM_DURATION_IN_DAYS = 180;

  private static final String BEGIN_PERIOD_START_DATE = "beginPeriodStartDate";

  private static final String BEGIN_PERIOD_END_DATE = "beginPeriodEndDate";

  private static final String COMPLETION_PERIOD_START_DATE = "completionPeriodStartDate";

  private static final String COMPLETION_PERIOD_END_DATE = "completionPeriodEndDate";

  @Autowired private HivMetadata hivMetadata;

  @Autowired private EPTSCalculationService ePTSCalculationService;

  @Override
  public CalculationResultMap evaluate(
      Collection<Integer> cohort,
      Map<String, Object> parameterValues,
      PatientCalculationContext context) {
    CalculationResultMap map = new CalculationResultMap();
    Location location = (Location) context.getFromCache("location");

    Date beginPeriodStartDate = getDateParameter(parameterValues, context, BEGIN_PERIOD_START_DATE);
    Date beginPeriodEndDate = getDateParameter(parameterValues, context, BEGIN_PERIOD_END_DATE);
    Date completionPeriodStartDate =
        getDateParameter(parameterValues, context, COMPLETION_PERIOD_START_DATE);
    Date completionPeriodEndDate =
        getDateParameter(parameterValues, context, COMPLETION_PERIOD_END_DATE);

    final List<EncounterType> consultationEncounterTypes =
        Arrays.asList(
            hivMetadata.getAdultoSeguimentoEncounterType(),
            hivMetadata.getARVPediatriaSeguimentoEncounterType());
    CalculationResultMap startProfilaxiaObservations =
        ePTSCalculationService.firstObs(
            hivMetadata.getDataInicioProfilaxiaIsoniazidaConcept(),
            null,
            location,
            false,
            beginPeriodStartDate,
            beginPeriodEndDate,
            cohort,
            context);
    CalculationResultMap endProfilaxiaObservations =
        ePTSCalculationService.lastObs(
            hivMetadata.getDataFinalizacaoProfilaxiaIsoniazidaConcept(),
            null,
            location,
            false,
            completionPeriodStartDate,
            completionPeriodEndDate,
            cohort,
            context);
    CalculationResultMap isoniazidUsageObservationsList =
        ePTSCalculationService.allObservations(
            hivMetadata.getIsoniazidUsageConcept(),
            hivMetadata.getYesConcept(),
            consultationEncounterTypes,
            location,
            cohort,
            context);

    for (Integer patientId : cohort) {
      Obs startProfilaxiaObs =
          EptsCalculationUtils.resultForPatient(startProfilaxiaObservations, patientId);
      Obs endProfilaxiaObs =
          EptsCalculationUtils.resultForPatient(endProfilaxiaObservations, patientId);
      Date startDate = getDateFromObs(startProfilaxiaObs);
      Date endDate = getDateFromObs(endProfilaxiaObs);
      boolean inconsistent =
          (startDate != null && endDate != null && startDate.compareTo(endDate) > 0)
              || (startDate == null && endDate != null);
      if (!inconsistent && startDate != null) {
        boolean completed = startDate != null && endDate != null;
        if (completed) {
          int profilaxiaDuration =
              Days.daysIn(new Interval(startDate.getTime(), endDate.getTime())).getDays();
          if (profilaxiaDuration >= MINIMUM_DURATION_IN_DAYS) {
            map.put(patientId, new BooleanResult(true, this));
          }
        } else {
          int yesAnswers =
              calculateNumberOfYesAnswers(isoniazidUsageObservationsList, patientId, startDate);
          if (yesAnswers >= NUMBER_ISONIAZID_USAGE_TO_CONSIDER_COMPLETED) {
            map.put(patientId, new BooleanResult(true, this));
          }
        }
      }
    }
    return map;
  }

  private int calculateNumberOfYesAnswers(
      CalculationResultMap isoniazidUsageObservationsList, Integer patientId, Date startDate) {
    List<Obs> isoniazidUsageObservations =
        EptsCalculationUtils.extractResultValues(
            (ListResult) isoniazidUsageObservationsList.get(patientId));
    int count = 0;
    Date isoniazidUsageEndDate = getIsoniazidUsageEndDate(startDate);
    for (Obs obs : isoniazidUsageObservations) {
      Date date = obs.getObsDatetime();
      if (date.compareTo(startDate) >= 0 && date.compareTo(isoniazidUsageEndDate) <= 0) {
        count++;
      }
    }
    return count;
  }

  private Date getIsoniazidUsageEndDate(Date startDate) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(startDate);
    calendar.add(Calendar.MONTH, MONTHS_TO_CHECK_FOR_ISONIAZID_USAGE);
    return calendar.getTime();
  }

  private Date getDateFromObs(Obs obs) {
    if (obs != null) {
      return obs.getValueDatetime();
    }
    return null;
  }

  private Date getDateParameter(
      Map<String, Object> parameterValues,
      PatientCalculationContext context,
      String parameterName) {
    Date date = null;
    if (parameterValues != null) {
      date = (Date) parameterValues.get(parameterName);
    }
    if (date == null) {
      date = (Date) context.getFromCache(parameterName);
    }
    return date;
  }
}
