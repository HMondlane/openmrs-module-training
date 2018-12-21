/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.eptsreports.reporting.calculation.pvls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.api.context.Context;
import org.openmrs.calculation.patient.PatientCalculationContext;
import org.openmrs.calculation.result.CalculationResultMap;
import org.openmrs.calculation.result.ListResult;
import org.openmrs.calculation.result.SimpleResult;
import org.openmrs.module.eptsreports.metadata.HivMetadata;
import org.openmrs.module.eptsreports.reporting.calculation.AbstractPatientCalculation;
import org.openmrs.module.eptsreports.reporting.calculation.BooleanResult;
import org.openmrs.module.eptsreports.reporting.calculation.EptsCalculations;
import org.openmrs.module.eptsreports.reporting.utils.EptsCalculationUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RoutineForAdultsAndChildrenCalculation extends AbstractPatientCalculation {
	
	@Autowired
	private HivMetadata hivMetadata;
	
	/**
	 * Patients on ART for the last X months with one VL result registered in the 12 month period
	 * Between Y to Z months after ART initiation TODO: merge with
	 * RoutineForBreastfeedingAndPregnantWomenCalculation definition
	 * 
	 * @param cohort
	 * @param params
	 * @param context
	 * @return CalculationResultMap
	 */
	@Override
	public CalculationResultMap evaluate(Collection<Integer> cohort, Map<String, Object> params, PatientCalculationContext context) {
		
		CalculationResultMap map = new CalculationResultMap();
		
		Concept viralLoad = hivMetadata.getHivViralLoadConcept();
		Concept regime = hivMetadata.getRegimeConcept();
		
		EncounterType arvAdultoEncounterType = hivMetadata.getAdultoSeguimentoEncounterType(); // encounter 6
		EncounterType arvPediatriaEncounterType = hivMetadata.getARVPediatriaSeguimentoEncounterType(); // encounter 9
		
		// get the ART initiation date
		CalculationResultMap arvsInitiationDateMap = calculate(
		    Context.getRegisteredComponents(InitialArtStartDateCalculation.class).get(0), cohort, context);
		CalculationResultMap patientHavingVL = EptsCalculations.allObs(viralLoad, cohort, context);
		CalculationResultMap changingRegimenLines = EptsCalculations.lastObs(regime, cohort, context);
		CalculationResultMap lastVl = EptsCalculations.lastObs(viralLoad, cohort, context);
		
		// get first encounter for the option c
		CalculationResultMap firstAdultoEncounter = EptsCalculations.firstEncounter(arvAdultoEncounterType, cohort, context);
		CalculationResultMap firstPediatriaEncounter = EptsCalculations.firstEncounter(arvPediatriaEncounterType, cohort, context);
		
		for (Integer pId : cohort) {
			boolean isOnRoutine = false;
			Date artInitiationDate = null;
			List<Obs> viralLoadForPatientTakenWithin12Months = new ArrayList<Obs>();
			SimpleResult artStartDateResult = (SimpleResult) arvsInitiationDateMap.get(pId);
			// get all the VL results for each patient in the last 12 months
			ListResult vlObsResult = (ListResult) patientHavingVL.get(pId);
			Obs lastVlObs = EptsCalculationUtils.obsResultForPatient(lastVl, pId);
			
			if (artStartDateResult != null) {
				artInitiationDate = (Date) artStartDateResult.getValue();
			}
			// check that this patient should be on ART for more than six months
			
			if (artInitiationDate != null && lastVlObs != null && lastVlObs.getObsDatetime() != null) {
				Date latestVlLowerDateLimit = EptsCalculationUtils.addMonths(context.getNow(), -12);
				if (lastVlObs.getObsDatetime().after(latestVlLowerDateLimit) && lastVlObs.getObsDatetime().before(context.getNow())) {
					
					if (vlObsResult != null && !vlObsResult.isEmpty()) {
						List<Obs> vLoadList = EptsCalculationUtils.extractResultValues(vlObsResult);
						
						if (vLoadList.size() > 0) {
							for (Obs obs : vLoadList) {
								if (obs != null && obs.getObsDatetime().after(latestVlLowerDateLimit)
								        && obs.getObsDatetime().before(context.getNow())) {
									viralLoadForPatientTakenWithin12Months.add(obs);
								}
							}
						}
					}
				}
				
				// find out for criteria 1 a
				if (viralLoadForPatientTakenWithin12Months.size() == 1) {
					// the patients should be 6 to 9 months after ART initiation
					// get the obs date for this VL and compare that with the provided dates
					Obs vlObs = viralLoadForPatientTakenWithin12Months.get(0);
					if (vlObs != null && vlObs.getObsDatetime() != null) {
						Date vlDate = vlObs.getObsDatetime();
						if (EptsCalculationUtils.monthsSince(artInitiationDate, vlDate) > 6
						        && EptsCalculationUtils.monthsSince(artInitiationDate, vlDate) <= 9) {
							isOnRoutine = true;
						}
					}
				}
				
				// find out criteria 2
				if (viralLoadForPatientTakenWithin12Months.size() > 1) {
					
					Collections.sort(viralLoadForPatientTakenWithin12Months, new Comparator<Obs>() {
						
						public int compare(Obs obs1, Obs obs2) {
							return obs1.getObsId().compareTo(obs2.getObsId());
						}
					});
					Obs previousObs = viralLoadForPatientTakenWithin12Months.get(viralLoadForPatientTakenWithin12Months.size() - 2);
					Obs currentObs = viralLoadForPatientTakenWithin12Months.get(viralLoadForPatientTakenWithin12Months.size() - 1);
					if (currentObs != null && previousObs != null && previousObs.getValueNumeric() != null
					        && previousObs.getObsDatetime() != null && previousObs.getValueNumeric() < 1000
					        && currentObs.getObsDatetime() != null
					        && previousObs.getObsDatetime().before(currentObs.getObsDatetime())) {
						if (EptsCalculationUtils.monthsSince(previousObs.getObsDatetime(), currentObs.getObsDatetime()) >= 12
						        && EptsCalculationUtils.monthsSince(previousObs.getObsDatetime(), currentObs.getObsDatetime()) <= 15) {
							isOnRoutine = true;
						}
					}
				}
				
				// find out criteria 3
				if (!isOnRoutine && viralLoadForPatientTakenWithin12Months.size() > 0) {
					// get when a patient switch between lines from first to second
					// Date when started on second line will be considered the changing date
					Obs obs = EptsCalculationUtils.obsResultForPatient(changingRegimenLines, pId);
					Encounter adultoEncounter = EptsCalculationUtils.encounterResultForPatient(firstAdultoEncounter, pId);
					Encounter pediatriaEncounter = EptsCalculationUtils.encounterResultForPatient(firstPediatriaEncounter, pId);
					// loop through the viral load list and find one that is after the second line
					// option
					Date finalDate = null;
					if (adultoEncounter != null) {
						finalDate = adultoEncounter.getEncounterDatetime();
					}
					if (finalDate == null && pediatriaEncounter != null) {
						finalDate = pediatriaEncounter.getEncounterDatetime();
					}
					Date latestVlDate = lastVlObs.getObsDatetime();
					if (obs != null && latestVlDate != null && finalDate != null) {
						if (obs.getObsDatetime().before(latestVlDate)) {
							isOnRoutine = true;
							// check that there is no other VL registered between first encounter_date and
							// vl_registered_date
							// get list of all vls for this patient
							List<Obs> allVlsForPatient = EptsCalculationUtils.extractResultValues(vlObsResult);
							// loop through the vls and exclude the patient if they have an obs falling
							// between the 2 dates
							for (Obs obs1 : allVlsForPatient) {
								if (obs1.getObsDatetime() != null && obs1.getObsDatetime().after(finalDate)
								        && obs1.getObsDatetime().before(latestVlDate)) {
									isOnRoutine = false;
									break;
								}
							}
						}
					}
				}
				
			}
			map.put(pId, new BooleanResult(isOnRoutine, this));
		}
		
		return map;
	}
}
