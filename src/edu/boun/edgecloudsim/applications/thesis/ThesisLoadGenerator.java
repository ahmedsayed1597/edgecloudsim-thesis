/*
 * Title:        EdgeCloudSim - Thesis Load Generator (seeded idle/active)
 *
 * Description:
 * Seeded re-implementation of EdgeCloudSim's IdleActiveLoadGenerator. The generation
 * logic is identical to the upstream model; the only difference is that every random
 * draw comes from a generator seeded by an explicit value, so the same seed always
 * yields the same task arrival sequence.
 *
 * Rationale is the same as ThesisMobilityModel: the two placement strategies must see
 * an identical workload within a repetition for the comparison to be paired.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.thesis;

import java.util.ArrayList;
import java.util.Random;

import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.TaskProperty;

/**
 * Idle/active Poisson load generation with deterministic, seed-controlled random draws.
 */
public class ThesisLoadGenerator extends LoadGeneratorModel {
	/** Application type assigned to each device for the whole simulation. */
	private int[] taskTypeOfDevices;

	/** Seed governing every random draw in this model. */
	private final long seed;

	public ThesisLoadGenerator(int _numberOfMobileDevices, double _simulationTime,
			String _simScenario, long _seed) {
		super(_numberOfMobileDevices, _simulationTime, _simScenario);
		seed = _seed;
	}

	@Override
	public void initializeModel() {
		taskList = new ArrayList<TaskProperty>();

		// Offset from the mobility seed so the workload stream is independent of the
		// movement stream while both stay reproducible.
		RandomGenerator sizeRng = new Well19937c(seed + 7919);
		RandomGenerator arrivalRng = new Well19937c(seed + 6271);
		Random selectorRng = new Random(seed + 4523);

		double[][] taskTable = SimSettings.getInstance().getTaskLookUpTable();

		// [task_type][0]=input size, [1]=output size, [2]=task length
		ExponentialDistribution[][] expRngList = new ExponentialDistribution[taskTable.length][3];
		for (int i = 0; i < taskTable.length; i++) {
			if (taskTable[i][0] == 0)
				continue;

			expRngList[i][0] = new ExponentialDistribution(sizeRng, taskTable[i][5]);
			expRngList[i][1] = new ExponentialDistribution(sizeRng, taskTable[i][6]);
			expRngList[i][2] = new ExponentialDistribution(sizeRng, taskTable[i][7]);
		}

		taskTypeOfDevices = new int[numberOfMobileDevices];
		for (int i = 0; i < numberOfMobileDevices; i++) {
			// Weighted selection of this device's application type.
			int randomTaskType = -1;
			double taskTypeSelector = selectorRng.nextDouble() * 100;
			double taskTypePercentage = 0;
			for (int j = 0; j < taskTable.length; j++) {
				taskTypePercentage += taskTable[j][0];
				if (taskTypeSelector <= taskTypePercentage) {
					randomTaskType = j;
					break;
				}
			}

			if (randomTaskType == -1) {
				SimLogger.printLine("Critical Error: No valid task type assigned to device " + i + "!");
				continue;
			}

			taskTypeOfDevices[i] = randomTaskType;

			double poissonMean = taskTable[randomTaskType][2];
			double activePeriod = taskTable[randomTaskType][3];
			double idlePeriod = taskTable[randomTaskType][4];

			// Stagger the first active period so devices do not all start together.
			double activePeriodStartTime = SimSettings.CLIENT_ACTIVITY_START_TIME
					+ selectorRng.nextDouble() * activePeriod;
			double virtualTime = activePeriodStartTime;

			ExponentialDistribution rng = new ExponentialDistribution(arrivalRng, poissonMean);

			while (virtualTime < simulationTime) {
				double interval = rng.sample();

				if (interval <= 0) {
					SimLogger.printLine("Warning: Invalid interval " + interval
							+ " for device " + i + " at time " + virtualTime);
					continue;
				}

				virtualTime += interval;

				// Past the end of this active period: skip forward over the idle gap.
				if (virtualTime > activePeriodStartTime + activePeriod) {
					activePeriodStartTime = activePeriodStartTime + activePeriod + idlePeriod;
					virtualTime = activePeriodStartTime;
					continue;
				}

				taskList.add(new TaskProperty(i, randomTaskType, virtualTime, expRngList));
			}
		}
	}

	@Override
	public int getTaskTypeOfDevice(int deviceId) {
		return taskTypeOfDevices[deviceId];
	}
}
