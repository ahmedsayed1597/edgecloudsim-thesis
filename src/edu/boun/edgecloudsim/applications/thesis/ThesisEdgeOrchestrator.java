/*
 * Title:        EdgeCloudSim - Thesis Edge Orchestrator
 *
 * Description:
 * Implements CENTRALIZED and DECENTRALIZED edge placement as ONE class sharing ONE
 * selection routine. This is deliberate: the thesis's whole premise is a controlled
 * comparison of control topology, not of placement algorithms, so the only permitted
 * differences between the two policies are:
 *
 *   1. Which hosts each can see  (CENTRALIZED: all hosts. DECENTRALIZED: only hosts in
 *      the requesting user's region)
 *   2. Decision delay            (exposed via getDecisionDelay() for
 *      ThesisMobileDeviceManager to charge against simulated time in Step 3)
 *
 * If the two policies ever needed different scoring or VM-selection logic, the
 * comparison would be measuring algorithm differences instead of topology differences,
 * which would collapse the thesis premise. Keeping them as one class with a single
 * selectVm() makes that constraint structural rather than a promise.
 *
 * VM selection itself (least-loaded / worst-fit among the visible candidate hosts) is
 * carried over unchanged from tutorial3's SampleEdgeOrchestrator.
 *
 * Region assignment: edge hosts are partitioned into region_count contiguous groups by
 * WLAN id rank. A task's home region is its submission location's WLAN id, partitioned
 * the same way, so a DECENTRALIZED task always considers exactly the hosts in its own
 * region regardless of how region_count is configured.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2022, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.thesis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeHost;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import edu.boun.edgecloudsim.edge_client.CpuUtilizationModel_Custom;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.utils.SimLogger;

public class ThesisEdgeOrchestrator extends EdgeOrchestrator {
	public static final String CENTRALIZED = "CENTRALIZED";
	public static final String DECENTRALIZED = "DECENTRALIZED";

	private final ThesisConfig config;
	// THESIS CHANGE 2026-08-25: seeded RNG for randomized tie-break in selectVm(), see
	// the class-level javadoc addendum below. Independent stream from the mobility and
	// load-generation seeds (different offset), so it cannot perturb the paired trace.
	private final Random tieBreakRng;

	private int numberOfHost;
	/** hostRegion[hostId] = region index, 0..regionCount-1. */
	private int[] hostRegion;
	/** wlanRegion[wlanId] = region index, using the same partition as hostRegion. */
	private int[] wlanRegion;
	/**
	 * Region map cannot be built in initialize(): SimManager runs
	 * scenarioFactory.getEdgeOrchestrator().initialize() BEFORE it creates the edge
	 * server manager and before SimManager.getInstance() is even set (see
	 * SimManager's constructor), so the host list this map depends on does not exist
	 * yet. Built lazily on first use instead - see ensureRegionMapBuilt().
	 */
	private boolean regionMapBuilt = false;

	public ThesisEdgeOrchestrator(String _policy, String _simScenario, ThesisConfig _config, long _seed) {
		super(_policy, _simScenario);
		config = _config;
		tieBreakRng = new Random(_seed + 3187);
	}

	@Override
	public void initialize() {
		numberOfHost = SimSettings.getInstance().getNumOfEdgeHosts();

		if (!policy.equals(CENTRALIZED) && !policy.equals(DECENTRALIZED)) {
			SimLogger.printLine("Unknown edge orchestrator policy '" + policy
					+ "'! Expected CENTRALIZED or DECENTRALIZED. Terminating simulation...");
			System.exit(0);
		}

	}

	private void ensureRegionMapBuilt() {
		if (regionMapBuilt)
			return;
		buildRegionMap();
		regionMapBuilt = true;
	}

	/**
	 * Assigns every host, and every WLAN id a task might be submitted from, to one of
	 * region_count contiguous regions. Contiguous (rather than round-robin) grouping
	 * matches this topology's datacenters being laid out along a line, so each region
	 * is a geographically compact cluster - the natural read of "region" for a
	 * decentralized controller with a partial view.
	 */
	private void buildRegionMap() {
		int regionCount = config.getRegionCount();

		// Collect each host's serving WLAN id, in host-id order.
		int[] hostWlanId = new int[numberOfHost];
		List<Host> hosts = new ArrayList<Host>();
		for (org.cloudbus.cloudsim.Datacenter dc : SimManager.getInstance().getEdgeServerManager().getDatacenterList()) {
			hosts.addAll(dc.getHostList());
		}
		for (Host h : hosts) {
			EdgeHost edgeHost = (EdgeHost) h;
			hostWlanId[h.getId()] = edgeHost.getLocation().getServingWlanId();
		}

		// Rank distinct WLAN ids so the partition is well-defined even if WLAN ids are
		// not already a dense 0..N-1 range.
		List<Integer> distinctWlanIds = new ArrayList<Integer>();
		for (int wlanId : hostWlanId) {
			if (!distinctWlanIds.contains(wlanId))
				distinctWlanIds.add(wlanId);
		}
		Collections.sort(distinctWlanIds);

		int maxWlanId = Collections.max(distinctWlanIds);
		wlanRegion = new int[maxWlanId + 1];
		int hostsPerRegion = (int) Math.ceil((double) distinctWlanIds.size() / regionCount);
		for (int rank = 0; rank < distinctWlanIds.size(); rank++) {
			int region = Math.min(rank / hostsPerRegion, regionCount - 1);
			wlanRegion[distinctWlanIds.get(rank)] = region;
		}

		hostRegion = new int[numberOfHost];
		for (int hostId = 0; hostId < numberOfHost; hostId++) {
			hostRegion[hostId] = wlanRegion[hostWlanId[hostId]];
		}
	}

	/**
	 * Simulated-time cost of this orchestrator's placement decision. Read by
	 * ThesisMobileDeviceManager (Step 3) and charged against simulated time before the
	 * upload starts - not the wall-clock nanoTime() overhead upstream EdgeCloudSim
	 * logs, which has no effect on the simulation and would silently discard the
	 * decision-delay half of the experimental design.
	 */
	public double getDecisionDelay() {
		return policy.equals(CENTRALIZED)
				? config.getCentralizedDecisionDelay()
				: config.getDecentralizedDecisionDelay();
	}

	/**
	 * Region index for a WLAN id, using the same partition getCandidateHosts() uses for
	 * DECENTRALIZED visibility. Exposed for migration detection (Stage 1, thesis
	 * mobility work): a device's current region is derived from its serving WLAN id via
	 * this same map, so "device region" and "candidate host region" are guaranteed to be
	 * the same partition - not a parallel one that could silently drift out of sync.
	 */
	public int getWlanRegion(int wlanId) {
		ensureRegionMapBuilt();
		return wlanRegion[wlanId];
	}

	/**
	 * Region index for a host id, using the same partition getCandidateHosts() uses.
	 * Exposed for migration detection (Stage 1): the pinned server's region is looked up
	 * through this method so it can be compared against getWlanRegion() for the device's
	 * current location.
	 */
	public int getHostRegion(int hostId) {
		ensureRegionMapBuilt();
		return hostRegion[hostId];
	}

	/** THESIS ADDITION - mobility Stage 2: exposes the policy name for migration logging. */
	public String getPolicy() {
		return policy;
	}

	@Override
	public int getDeviceToOffload(Task task) {
		// This study is edge-only by design (applications.xml sets
		// prob_cloud_selection=0 for both service classes): the comparison is about
		// WHICH edge node handles a task, not whether it goes to the cloud.
		return SimSettings.GENERIC_EDGE_DEVICE_ID;
	}

	/**
	 * The hosts this orchestrator is allowed to consider for a task. This is the ONLY
	 * point where CENTRALIZED and DECENTRALIZED diverge in visibility.
	 */
	private List<Integer> getCandidateHosts(Task task) {
		ensureRegionMapBuilt();

		List<Integer> candidates = new ArrayList<Integer>();

		if (policy.equals(CENTRALIZED)) {
			for (int hostId = 0; hostId < numberOfHost; hostId++)
				candidates.add(hostId);
		} else {
			int taskRegion = wlanRegion[task.getSubmittedLocation().getServingWlanId()];
			for (int hostId = 0; hostId < numberOfHost; hostId++) {
				if (hostRegion[hostId] == taskRegion)
					candidates.add(hostId);
			}
		}

		return candidates;
	}

	@Override
	public Vm getVmToOffload(Task task, int deviceId) {
		if (deviceId != SimSettings.GENERIC_EDGE_DEVICE_ID) {
			SimLogger.printLine("Unknown device id! The simulation has been terminated.");
			System.exit(0);
		}

		return selectVm(getCandidateHosts(task), task);
	}

	/**
	 * Least-loaded (worst-fit) VM selection among the given candidate hosts: picks the
	 * VM with the largest residual CPU capacity that can still fit the task. Logic
	 * carried over unchanged from tutorial3's SampleEdgeOrchestrator - this is the part
	 * that must stay identical between CENTRALIZED and DECENTRALIZED for the
	 * comparison to isolate visibility rather than algorithm quality.
	 */
	private Vm selectVm(List<Integer> candidateHostIds, Task task) {

		List<Vm> bestVms = new ArrayList<Vm>();
		double bestCapacity = 0; // start with min value, same threshold as upstream

		for (int hostId : candidateHostIds) {
			List<EdgeVM> vmArray = SimManager.getInstance().getEdgeServerManager().getVmList(hostId);
			for (int vmIndex = 0; vmIndex < vmArray.size(); vmIndex++) {
				EdgeVM vm = vmArray.get(vmIndex);
				double requiredCapacity = ((CpuUtilizationModel_Custom) task.getUtilizationModelCpu())
						.predictUtilization(vm.getVmType());
				double targetVmCapacity = (double) 100
						- vm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());

				if (requiredCapacity <= targetVmCapacity) {
					if (targetVmCapacity > bestCapacity) {
						bestCapacity = targetVmCapacity;
						bestVms.clear();
						bestVms.add(vm);
					} else if (targetVmCapacity == bestCapacity) {
						bestVms.add(vm);
					}
				}
			}
		}

		if (bestVms.isEmpty())
			return null;

		return bestVms.get(tieBreakRng.nextInt(bestVms.size()));
	}

	@Override
	public void processEvent(SimEvent arg0) {
		// Stateless orchestrator - nothing to do.
	}

	@Override
	public void shutdownEntity() {
		// Nothing to release.
	}

	@Override
	public void startEntity() {
		// Nothing to schedule at startup.
	}
}
