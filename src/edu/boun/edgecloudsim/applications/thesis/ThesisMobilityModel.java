/*
 * Title:        EdgeCloudSim - Thesis Mobility Model (seeded nomadic)
 *
 * Description:
 * Seeded re-implementation of EdgeCloudSim's NomadicMobility. The movement logic is
 * identical to the upstream model; the only difference is that every random draw comes
 * from a generator seeded by an explicit value, so the same seed always yields the same
 * mobility trace.
 *
 * This exists because the two placement strategies under study must be compared on the
 * SAME mobility trace within a repetition. Upstream EdgeCloudSim seeds from
 * System.currentTimeMillis(), which makes the comparison unpaired and invalidates the
 * paired statistics planned for the full study.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.thesis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;

/**
 * Nomadic mobility with deterministic, seed-controlled random draws.
 */
public class ThesisMobilityModel extends MobilityModel {
	/** Timeline of location changes for each device. */
	private List<TreeMap<Double, Location>> treeMapArray;

	/** Seed governing every random draw in this model. */
	private final long seed;

	public ThesisMobilityModel(int _numberOfMobileDevices, double _simulationTime, long _seed) {
		super(_numberOfMobileDevices, _simulationTime);
		seed = _seed;
	}

	@Override
	public void initialize() {
		treeMapArray = new ArrayList<TreeMap<Double, Location>>();

		// Two independent seeded streams: one for dwell times, one for place selection.
		// Offsetting the place-selection seed keeps the two streams from correlating.
		RandomGenerator dwellRng = new Well19937c(seed);
		Random placeRng = new Random(seed + 991);

		int numOfDatacenters = SimSettings.getInstance().getNumOfEdgeDatacenters();
		ExponentialDistribution[] expRngList = new ExponentialDistribution[numOfDatacenters];

		Document doc = SimSettings.getInstance().getEdgeDevicesDocument();
		NodeList datacenterList = doc.getElementsByTagName("datacenter");

		// Mean dwell time per datacenter is looked up from its attractiveness level.
		for (int i = 0; i < datacenterList.getLength(); i++) {
			Element datacenterElement = (Element) datacenterList.item(i);
			Element location = (Element) datacenterElement.getElementsByTagName("location").item(0);
			int placeTypeIndex = Integer.parseInt(
					location.getElementsByTagName("attractiveness").item(0).getTextContent());

			expRngList[i] = new ExponentialDistribution(dwellRng,
					SimSettings.getInstance().getMobilityLookUpTable()[placeTypeIndex]);
		}

		// Initial placement.
		for (int i = 0; i < numberOfMobileDevices; i++) {
			treeMapArray.add(i, new TreeMap<Double, Location>());

			int randDatacenterId = placeRng.nextInt(numOfDatacenters);
			treeMapArray.get(i).put(SimSettings.CLIENT_ACTIVITY_START_TIME,
					readLocation(datacenterList, randDatacenterId));
		}

		// Movement timeline for the whole simulation.
		for (int i = 0; i < numberOfMobileDevices; i++) {
			TreeMap<Double, Location> treeMap = treeMapArray.get(i);

			while (treeMap.lastKey() < SimSettings.getInstance().getSimulationTime()) {
				int currentLocationId = treeMap.lastEntry().getValue().getServingWlanId();
				double waitingTime = expRngList[currentLocationId].sample();

				// Move somewhere else (unless there is only one place to be).
				int newDatacenterId;
				do {
					newDatacenterId = placeRng.nextInt(numOfDatacenters);
				} while (numOfDatacenters > 1 && newDatacenterId == currentLocationId);

				treeMap.put(treeMap.lastKey() + waitingTime,
						readLocation(datacenterList, newDatacenterId));
			}
		}
	}

	/** Reads one datacenter's location block out of edge_devices.xml. */
	private Location readLocation(NodeList datacenterList, int datacenterId) {
		Node datacenterNode = datacenterList.item(datacenterId);
		Element datacenterElement = (Element) datacenterNode;
		Element location = (Element) datacenterElement.getElementsByTagName("location").item(0);

		int placeTypeIndex = Integer.parseInt(
				location.getElementsByTagName("attractiveness").item(0).getTextContent());
		int wlanId = Integer.parseInt(location.getElementsByTagName("wlan_id").item(0).getTextContent());
		int xPos = Integer.parseInt(location.getElementsByTagName("x_pos").item(0).getTextContent());
		int yPos = Integer.parseInt(location.getElementsByTagName("y_pos").item(0).getTextContent());

		return new Location(placeTypeIndex, wlanId, xPos, yPos);
	}

	@Override
	public Location getLocation(int deviceId, double time) {
		TreeMap<Double, Location> treeMap = treeMapArray.get(deviceId);
		Entry<Double, Location> e = treeMap.floorEntry(time);

		if (e == null) {
			SimLogger.printLine("impossible is occurred! no location is found for the device '"
					+ deviceId + "' at " + time);
			System.exit(1);
		}

		return e.getValue();
	}
}
