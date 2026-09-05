/*
 * Title:        EdgeCloudSim - Network Model
 * 
 * Description: 
 * ThesisNetworkModel uses
 * -> the result of an empirical study for the WLAN and WAN delays
 * The experimental network model is developed
 * by taking measurements from the real life deployments.
 *   
 * -> MMPP/MMPP/1 queue model for MAN delay
 * MAN delay is observed via a single server queue model with
 * Markov-modulated Poisson process (MMPP) arrivals.
 *   
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.thesis;

import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;

public class ThesisNetworkModel extends NetworkModel {
	public static enum NETWORK_TYPE {WLAN, LAN};
	public static enum LINK_TYPE {DOWNLOAD, UPLOAD};
	// THESIS CHANGE: MAN bandwidth is now read from the 'man_bandwidth' config property
	// (Mbps) instead of being hardcoded at 1300 Mbps. It represents the share of metro
	// backhaul capacity allocated to this service, not the whole ring. Left as a static
	// field so the rest of the upstream class is untouched.
	public static double MAN_BW = SimSettings.getInstance().getManBandwidth(); //Kbps effective MAN bandwidth (shared)

	@SuppressWarnings("unused")
	private int manClients; // concurrent MAN relay sessions (aggregate)
	private int[] wanClients;
	private int[] wlanClients;
	
	private double lastMM1QueueUpdateTime; // last time we reset MAN statistics
	private double ManPoissonMeanForDownload; // average inter-arrival (s) for MAN downloads (adaptive)
	private double ManPoissonMeanForUpload;   // average inter-arrival (s) for MAN uploads (adaptive)

	private double avgManTaskInputSize;  // average MAN task input size (KB) over last window
	private double avgManTaskOutputSize; // average MAN task output size (KB) over last window

	//record last n task statistics during MM1_QUEUE_MODEL_UPDATE_INTEVAL seconds to simulate mmpp/m/1 queue model
	private double totalManTaskInputSize;     // cumulative input KB this window
	private double totalManTaskOutputSize;    // cumulative output KB this window
	private double numOfManTaskForDownload;   // download task count this window
	private double numOfManTaskForUpload;     // upload task count this window
	
	public static final double[] experimentalWlanDelay = {
		/*1 Client*/ 88040.279 /*(Kbps)*/,
		/*2 Clients*/ 45150.982 /*(Kbps)*/,
		/*3 Clients*/ 30303.641 /*(Kbps)*/,
		/*4 Clients*/ 27617.211 /*(Kbps)*/,
		/*5 Clients*/ 24868.616 /*(Kbps)*/,
		/*6 Clients*/ 22242.296 /*(Kbps)*/,
		/*7 Clients*/ 20524.064 /*(Kbps)*/,
		/*8 Clients*/ 18744.889 /*(Kbps)*/,
		/*9 Clients*/ 17058.827 /*(Kbps)*/,
		/*10 Clients*/ 15690.455 /*(Kbps)*/,
		/*11 Clients*/ 14127.744 /*(Kbps)*/,
		/*12 Clients*/ 13522.408 /*(Kbps)*/,
		/*13 Clients*/ 13177.631 /*(Kbps)*/,
		/*14 Clients*/ 12811.330 /*(Kbps)*/,
		/*15 Clients*/ 12584.387 /*(Kbps)*/,
		/*15 Clients*/ 12135.161 /*(Kbps)*/,
		/*16 Clients*/ 11705.638 /*(Kbps)*/,
		/*17 Clients*/ 11276.116 /*(Kbps)*/,
		/*18 Clients*/ 10846.594 /*(Kbps)*/,
		/*19 Clients*/ 10417.071 /*(Kbps)*/,
		/*20 Clients*/ 9987.549 /*(Kbps)*/,
		/*21 Clients*/ 9367.587 /*(Kbps)*/,
		/*22 Clients*/ 8747.625 /*(Kbps)*/,
		/*23 Clients*/ 8127.663 /*(Kbps)*/,
		/*24 Clients*/ 7907.701 /*(Kbps)*/,
		/*25 Clients*/ 7887.739 /*(Kbps)*/,
		/*26 Clients*/ 7690.831 /*(Kbps)*/,
		/*27 Clients*/ 7393.922 /*(Kbps)*/,
		/*28 Clients*/ 7297.014 /*(Kbps)*/,
		/*29 Clients*/ 7100.106 /*(Kbps)*/,
		/*30 Clients*/ 6903.197 /*(Kbps)*/,
		/*31 Clients*/ 6701.986 /*(Kbps)*/,
		/*32 Clients*/ 6500.776 /*(Kbps)*/,
		/*33 Clients*/ 6399.565 /*(Kbps)*/,
		/*34 Clients*/ 6098.354 /*(Kbps)*/,
		/*35 Clients*/ 5897.143 /*(Kbps)*/,
		/*36 Clients*/ 5552.127 /*(Kbps)*/,
		/*37 Clients*/ 5207.111 /*(Kbps)*/,
		/*38 Clients*/ 4862.096 /*(Kbps)*/,
		/*39 Clients*/ 4517.080 /*(Kbps)*/,
		/*40 Clients*/ 4172.064 /*(Kbps)*/,
		/*41 Clients*/ 4092.922 /*(Kbps)*/,
		/*42 Clients*/ 4013.781 /*(Kbps)*/,
		/*43 Clients*/ 3934.639 /*(Kbps)*/,
		/*44 Clients*/ 3855.498 /*(Kbps)*/,
		/*45 Clients*/ 3776.356 /*(Kbps)*/,
		/*46 Clients*/ 3697.215 /*(Kbps)*/,
		/*47 Clients*/ 3618.073 /*(Kbps)*/,
		/*48 Clients*/ 3538.932 /*(Kbps)*/,
		/*49 Clients*/ 3459.790 /*(Kbps)*/,
		/*50 Clients*/ 3380.649 /*(Kbps)*/,
		/*51 Clients*/ 3274.611 /*(Kbps)*/,
		/*52 Clients*/ 3168.573 /*(Kbps)*/,
		/*53 Clients*/ 3062.536 /*(Kbps)*/,
		/*54 Clients*/ 2956.498 /*(Kbps)*/,
		/*55 Clients*/ 2850.461 /*(Kbps)*/,
		/*56 Clients*/ 2744.423 /*(Kbps)*/,
		/*57 Clients*/ 2638.386 /*(Kbps)*/,
		/*58 Clients*/ 2532.348 /*(Kbps)*/,
		/*59 Clients*/ 2426.310 /*(Kbps)*/,
		/*60 Clients*/ 2320.273 /*(Kbps)*/,
		/*61 Clients*/ 2283.828 /*(Kbps)*/,
		/*62 Clients*/ 2247.383 /*(Kbps)*/,
		/*63 Clients*/ 2210.939 /*(Kbps)*/,
		/*64 Clients*/ 2174.494 /*(Kbps)*/,
		/*65 Clients*/ 2138.049 /*(Kbps)*/,
		/*66 Clients*/ 2101.604 /*(Kbps)*/,
		/*67 Clients*/ 2065.160 /*(Kbps)*/,
		/*68 Clients*/ 2028.715 /*(Kbps)*/,
		/*69 Clients*/ 1992.270 /*(Kbps)*/,
		/*70 Clients*/ 1955.825 /*(Kbps)*/,
		/*71 Clients*/ 1946.788 /*(Kbps)*/,
		/*72 Clients*/ 1937.751 /*(Kbps)*/,
		/*73 Clients*/ 1928.714 /*(Kbps)*/,
		/*74 Clients*/ 1919.677 /*(Kbps)*/,
		/*75 Clients*/ 1910.640 /*(Kbps)*/,
		/*76 Clients*/ 1901.603 /*(Kbps)*/,
		/*77 Clients*/ 1892.566 /*(Kbps)*/,
		/*78 Clients*/ 1883.529 /*(Kbps)*/,
		/*79 Clients*/ 1874.492 /*(Kbps)*/,
		/*80 Clients*/ 1865.455 /*(Kbps)*/,
		/*81 Clients*/ 1833.185 /*(Kbps)*/,
		/*82 Clients*/ 1800.915 /*(Kbps)*/,
		/*83 Clients*/ 1768.645 /*(Kbps)*/,
		/*84 Clients*/ 1736.375 /*(Kbps)*/,
		/*85 Clients*/ 1704.106 /*(Kbps)*/,
		/*86 Clients*/ 1671.836 /*(Kbps)*/,
		/*87 Clients*/ 1639.566 /*(Kbps)*/,
		/*88 Clients*/ 1607.296 /*(Kbps)*/,
		/*89 Clients*/ 1575.026 /*(Kbps)*/,
		/*90 Clients*/ 1542.756 /*(Kbps)*/,
		/*91 Clients*/ 1538.544 /*(Kbps)*/,
		/*92 Clients*/ 1534.331 /*(Kbps)*/,
		/*93 Clients*/ 1530.119 /*(Kbps)*/,
		/*94 Clients*/ 1525.906 /*(Kbps)*/,
		/*95 Clients*/ 1521.694 /*(Kbps)*/,
		/*96 Clients*/ 1517.481 /*(Kbps)*/,
		/*97 Clients*/ 1513.269 /*(Kbps)*/,
		/*98 Clients*/ 1509.056 /*(Kbps)*/,
		/*99 Clients*/ 1504.844 /*(Kbps)*/,
		/*100 Clients*/ 1500.631 /*(Kbps)*/
	};
	
	public static final double[] experimentalWanDelay = {
		/*1 Client*/ 20703.973 /*(Kbps)*/,
		/*2 Clients*/ 12023.957 /*(Kbps)*/,
		/*3 Clients*/ 9887.785 /*(Kbps)*/,
		/*4 Clients*/ 8915.775 /*(Kbps)*/,
		/*5 Clients*/ 8259.277 /*(Kbps)*/,
		/*6 Clients*/ 7560.574 /*(Kbps)*/,
		/*7 Clients*/ 7262.140 /*(Kbps)*/,
		/*8 Clients*/ 7155.361 /*(Kbps)*/,
		/*9 Clients*/ 7041.153 /*(Kbps)*/,
		/*10 Clients*/ 6994.595 /*(Kbps)*/,
		/*11 Clients*/ 6653.232 /*(Kbps)*/,
		/*12 Clients*/ 6111.868 /*(Kbps)*/,
		/*13 Clients*/ 5570.505 /*(Kbps)*/,
		/*14 Clients*/ 5029.142 /*(Kbps)*/,
		/*15 Clients*/ 4487.779 /*(Kbps)*/,
		/*16 Clients*/ 3899.729 /*(Kbps)*/,
		/*17 Clients*/ 3311.680 /*(Kbps)*/,
		/*18 Clients*/ 2723.631 /*(Kbps)*/,
		/*19 Clients*/ 2135.582 /*(Kbps)*/,
		/*20 Clients*/ 1547.533 /*(Kbps)*/,
		/*21 Clients*/ 1500.252 /*(Kbps)*/,
		/*22 Clients*/ 1452.972 /*(Kbps)*/,
		/*23 Clients*/ 1405.692 /*(Kbps)*/,
		/*24 Clients*/ 1358.411 /*(Kbps)*/,
		/*25 Clients*/ 1311.131 /*(Kbps)*/
	};
	
	public ThesisNetworkModel(int _numberOfMobileDevices, String _simScenario) {
		super(_numberOfMobileDevices, _simScenario);
	}

	@Override
	public void initialize() {
		/**
		 * poisson_interarrival derivation (load-symmetry design)
		 * ---------------------------------------------------------------------
		 * Goal: both classes must place IDENTICAL compute load per device
		 * (1250 MI/sec), so any difference in results is caused by deadline
		 * strictness alone, not by one class simply loading the system harder
		 * than the other.
		 *
		 * Step 1: task_length is already fixed (25 MI for URLLC_LIKE, 250 MI for
		 *         EMBB_LIKE), derived separately from the measured 10 MI/ms VM
		 *         processing rate.
		 *
		 * Step 2: required tasks/sec = target load (MI/sec) / task_length (MI)
		 *           URLLC_LIKE: 1250 / 25  = 50 tasks/sec
		 *           EMBB_LIKE:  1250 / 250 =  5 tasks/sec
		 *
		 * Step 3: poisson_interarrival = 1 / (tasks/sec)
		 *         [config wants seconds BETWEEN tasks, not tasks per second,
		 *         so the rate is inverted]
		 *           URLLC_LIKE: 1 / 50 = 0.02
		 *           EMBB_LIKE:  1 / 5  = 0.2
		 *
		 * Result: URLLC_LIKE = many small tasks (0.02s apart), EMBB_LIKE = few
		 * large tasks (0.2s apart) — same total compute load, different
		 * granularity. The 10:1 ratio between the two interarrival values falls
		 * directly out of the 10:1 ratio already fixed in task_length; it is
		 * not chosen independently.
		 */
		wanClients = new int[SimSettings.getInstance().getNumOfEdgeDatacenters()];  //we have one access point for each datacenter
		wlanClients = new int[SimSettings.getInstance().getNumOfEdgeDatacenters()];  //we have one access point for each datacenter

		int numOfApp = SimSettings.getInstance().getTaskLookUpTable().length;
		SimSettings SS = SimSettings.getInstance();
		for(int taskIndex=0; taskIndex<numOfApp; taskIndex++) {
			if(SS.getTaskLookUpTable()[taskIndex][0] == 0) {
				SimLogger.printLine("Usage percentage of task " + taskIndex + " is 0! Terminating simulation...");
				System.exit(0);
			}
			else{
				double weight = SS.getTaskLookUpTable()[taskIndex][0]/(double)100;
				
				//assume half of the tasks use the MAN at the beginning
				ManPoissonMeanForDownload += ((SS.getTaskLookUpTable()[taskIndex][2])*weight) * 4;
				ManPoissonMeanForUpload = ManPoissonMeanForDownload;
				
				avgManTaskInputSize += SS.getTaskLookUpTable()[taskIndex][5]*weight;
				avgManTaskOutputSize += SS.getTaskLookUpTable()[taskIndex][6]*weight;
			}
		}

		ManPoissonMeanForDownload = ManPoissonMeanForDownload/numOfApp;
		ManPoissonMeanForUpload = ManPoissonMeanForUpload/numOfApp;
		avgManTaskInputSize = avgManTaskInputSize/numOfApp;
		avgManTaskOutputSize = avgManTaskOutputSize/numOfApp;
		
		// Initialize rolling window statistics
		lastMM1QueueUpdateTime = SimSettings.CLIENT_ACTIVITY_START_TIME;
		totalManTaskOutputSize = 0;
		numOfManTaskForDownload = 0;
		totalManTaskInputSize = 0;
		numOfManTaskForUpload = 0;
	}

    /**
    * source device is always mobile device in our simulation scenarios!
    */
	@Override
	public double getUploadDelay(int sourceDeviceId, int destDeviceId, Task task) {
		// Decision tree:
		//   MAN relay (edge->edge placeholder id pair)
		//   Mobile -> Cloud (WAN)
		//   Mobile -> Edge (WLAN)
		double delay = 0;
		
		//special case for man communication
		if(sourceDeviceId == destDeviceId && sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID){
			return delay = getManUploadDelay(task);
		}
		
		Location accessPointLocation = SimManager.getInstance().getMobilityModel().getLocation(sourceDeviceId,CloudSim.clock());

		//mobile device to cloud server
		if(destDeviceId == SimSettings.CLOUD_DATACENTER_ID){
			delay = getWanUploadDelay(accessPointLocation, task.getCloudletFileSize());
		}
		//mobile device to edge device (wifi access point)
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
			delay = getWlanUploadDelay(accessPointLocation, task.getCloudletFileSize());
		}
		
		return delay;
	}

    /**
    * destination device is always mobile device in our simulation scenarios!
    */
	@Override
	public double getDownloadDelay(int sourceDeviceId, int destDeviceId, Task task) {
		// Decision tree mirrors upload:
		//   MAN relay
		//   Cloud -> Mobile (WAN)
		//   Edge -> Mobile (WLAN)
		double delay = 0;
		
		//special case for man communication
		if(sourceDeviceId == destDeviceId && sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID){
			return delay = getManDownloadDelay(task);
		}
		
		Location accessPointLocation = SimManager.getInstance().getMobilityModel().getLocation(destDeviceId,CloudSim.clock());
		
		//cloud server to mobile device
		if(sourceDeviceId == SimSettings.CLOUD_DATACENTER_ID){
			delay = getWanDownloadDelay(accessPointLocation, task.getCloudletOutputSize());
		}
		//edge device (wifi access point) to mobile device
		else{
			delay = getWlanDownloadDelay(accessPointLocation, task.getCloudletOutputSize());
		}
		
		return delay;
	}

	@Override
	public void uploadStarted(Location accessPointLocation, int destDeviceId) {
		// Increment contention counters; must have matching uploadFinished for integrity
		if(destDeviceId == SimSettings.CLOUD_DATACENTER_ID)
			wanClients[accessPointLocation.getServingWlanId()]++;
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID)
			wlanClients[accessPointLocation.getServingWlanId()]++;
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID+1)
			manClients++;
		else {
			SimLogger.printLine("Error - unknown device id in uploadStarted(). Terminating simulation...");
			System.exit(0);
		}
	}

	@Override
	public void uploadFinished(Location accessPointLocation, int destDeviceId) {
		// Decrement counters; negative values would signal a logic bug
		if(destDeviceId == SimSettings.CLOUD_DATACENTER_ID)
			wanClients[accessPointLocation.getServingWlanId()]--;
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID)
			wlanClients[accessPointLocation.getServingWlanId()]--;
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID+1)
			manClients--;
		else {
			SimLogger.printLine("Error - unknown device id in uploadFinished(). Terminating simulation...");
			System.exit(0);
		}
	}

	@Override
	public void downloadStarted(Location accessPointLocation, int sourceDeviceId) {

		if(sourceDeviceId == SimSettings.CLOUD_DATACENTER_ID)
			wanClients[accessPointLocation.getServingWlanId()]++;
		else if (sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID)
			wlanClients[accessPointLocation.getServingWlanId()]++;
		else if (sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID+1)
			manClients++;
		else {
			SimLogger.printLine("Error - unknown device id in downloadStarted(). Terminating simulation...");
			System.exit(0);
		}
	}

	@Override
	public void downloadFinished(Location accessPointLocation, int sourceDeviceId) {
		// THESIS FIX 2026-08-25: see downloadStarted() above.
		if(sourceDeviceId == SimSettings.CLOUD_DATACENTER_ID)
			wanClients[accessPointLocation.getServingWlanId()]--;
		else if (sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID)
			wlanClients[accessPointLocation.getServingWlanId()]--;
		else if (sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID+1)
			manClients--;
		else {
			SimLogger.printLine("Error - unknown device id in downloadFinished(). Terminating simulation...");
			System.exit(0);
		}
	}

	private double getWlanDownloadDelay(Location accessPointLocation, double dataSize) {
		// Convert KB -> Kb and divide by empirical throughput (scaled by 3 for 802.11ac approximation)
		// Returns 0 if user index exceeds table => treated as bandwidth failure upstream
		int numOfWlanUser = wlanClients[accessPointLocation.getServingWlanId()];
		double taskSizeInKb = dataSize * (double)8; //KB to Kb
		double result=0;
		
		if(numOfWlanUser < experimentalWlanDelay.length)
			result = taskSizeInKb /*Kb*/ / (experimentalWlanDelay[numOfWlanUser] * (double) 3 ) /*Kbps*/; //802.11ac is around 3 times faster than 802.11n

		//System.out.println("--> " + numOfWlanUser + " user, " + taskSizeInKb + " KB, " +result + " sec");
		return result;
	}
	
	private double getWlanUploadDelay(Location accessPointLocation, double dataSize) {
		// Symmetric with download
		return getWlanDownloadDelay(accessPointLocation, dataSize);
	}
	
	private double getWanDownloadDelay(Location accessPointLocation, double dataSize) {
		// WAN throughput lookup (no scaling factor)
		int numOfWanUser = wanClients[accessPointLocation.getServingWlanId()];
		double taskSizeInKb = dataSize * (double)8; //KB to Kb
		double result=0;
		
		if(numOfWanUser < experimentalWanDelay.length)
			result = taskSizeInKb /*Kb*/ / (experimentalWanDelay[numOfWanUser]) /*Kbps*/;
		
		//System.out.println("--> " + numOfWanUser + " user, " + taskSizeInKb + " KB, " +result + " sec");
		
		return result;
	}
	
	private double getWanUploadDelay(Location accessPointLocation, double dataSize) {
		// Symmetric with download
		return getWanDownloadDelay(accessPointLocation, dataSize);
	}
	
	private double calculateMM1(double propagationDelay, double bandwidth /*Kbps*/, double PoissonMean,
	                            double avgTaskSize /*KB*/, int deviceCount){
		// M/M/1 delay approximation:
		//   λ = 1/PoissonMean (tasks/s) * deviceCount (aggregate arrival rate)
		//   μ = bandwidth(Kbps) / avgTaskSize(Kb)
		//   Expected system time = 1 / (μ - λ)
		// Add propagation delay then cap excessive (>15s) delays to 0 to indicate failure.
		// Negative (μ <= λ) => saturated -> return 0 (failure)
		double mu=0, lamda=0;
		
		avgTaskSize = avgTaskSize * 8; //convert from KB to Kb

        lamda = ((double)1/(double)PoissonMean); //task per seconds
		mu = bandwidth /*Kbps*/ / avgTaskSize /*Kb*/; //task per seconds
		double result = (double)1 / (mu-lamda*(double)deviceCount);
		
		if(result < 0)
			return 0;
		
		result += propagationDelay;
		
		return (result > 15) ? 0 : result;
	}
	
	private double getManDownloadDelay(Task task) {
		return getManDownloadTransferDelay(task.getCloudletOutputSize());
	}

	private double getManUploadDelay(Task task) {
		return getManUploadTransferDelay(task.getCloudletFileSize());
	}

	/**
	 * THESIS ADDITION - mobility Stage 2: size-parameterized extraction of
	 * getManDownloadDelay(Task)'s body, so a Stage 2 migration transfer (which has no
	 * Task object - it is an edge-to-edge state transfer, not a device request) can share
	 * the EXACT SAME adaptive MM1 queue/congestion state as real MAN task traffic,
	 * instead of a second parallel network model. getManDownloadDelay(Task) above is now
	 * a thin wrapper so existing task-based callers are unaffected.
	 */
	double getManDownloadTransferDelay(double dataSizeKB) {
		// Use adaptive parameters to compute current MAN download delay
		double result = calculateMM1(SimSettings.getInstance().getInternalLanDelay(),
				MAN_BW,
				ManPoissonMeanForDownload,
				avgManTaskOutputSize,
				numberOfMobileDevices);

		// Update rolling statistics for next interval adaptation - accumulate the real
		// transfer size (task or, for Stage 2, migration data) so avgManTaskOutputSize
		// keeps reflecting actual MAN traffic composition.
		totalManTaskOutputSize += dataSizeKB;
		numOfManTaskForDownload++;

		return result;
	}

	/** THESIS ADDITION - mobility Stage 2: see getManDownloadTransferDelay() above. */
	double getManUploadTransferDelay(double dataSizeKB) {
		// Symmetric logic for MAN upload path
		double result = calculateMM1(SimSettings.getInstance().getInternalLanDelay(),
				MAN_BW,
				ManPoissonMeanForUpload,
				avgManTaskInputSize,
				numberOfMobileDevices);

		totalManTaskInputSize += dataSizeKB;
		numOfManTaskForUpload++;

		return result;
	}

	/**
	 * THESIS ADDITION - mobility Stage 2 (bug fix): cross-region migration delay,
	 * DELIBERATELY separate from getManUploadTransferDelay() above.
	 *
	 * getManUploadTransferDelay() computes calculateMM1()'s mu (service rate) from
	 * avgManTaskInputSize - the POPULATION-AVERAGE size across all real MAN uploads
	 * (typically 0.5-15 KB per applications.xml). That is a pre-existing approximation
	 * this stage does not change for real tasks. But a migration transfer (1000s of KB,
	 * per migration_data_size_kb) is nowhere close to that average - reusing it would
	 * silently price a migration as if it were an average-sized task, making migration
	 * cost nearly free regardless of its actual configured size. That defeats Stage 2's
	 * entire purpose (architecture-dependent migration cost).
	 *
	 * This method instead passes the migration's OWN size as the mu argument, so the
	 * transfer is priced by its real weight while lambda (background arrival rate) still
	 * reflects existing real-task congestion - migration genuinely competes for capacity
	 * against that background load, it just isn't mispriced as a tiny task itself.
	 *
	 * Still updates the SAME adaptive accumulators as getManUploadTransferDelay(), so
	 * subsequent real-task calls continue to reflect migration's contribution to overall
	 * MAN traffic composition - this is not a parallel/disconnected model.
	 */
	double getManMigrationTransferDelay(double dataSizeKB) {
		double result = calculateMM1(SimSettings.getInstance().getInternalLanDelay(),
				MAN_BW,
				ManPoissonMeanForUpload,
				dataSizeKB,
				numberOfMobileDevices);

		totalManTaskInputSize += dataSizeKB;
		numOfManTaskForUpload++;

		return result;
	}

	/**
	 * THESIS ADDITION - mobility Stage 2: bandwidth-limited-only transfer delay for a
	 * SAME-REGION migration hop. Does NOT go through calculateMM1()'s M/M/1 queue -
	 * unlike a cross-region migration, a same-region hop is not modeled as contending
	 * with the shared MAN's queued task traffic, so DECENTRALIZED's region-restricted
	 * migrations stay cheap/reliable while CENTRALIZED's occasional cross-region
	 * migrations are exposed to the same congestion normal MAN-relayed tasks see. This
	 * reuses the EXISTING MAN_BW and lan_internal_delay values - no new bandwidth or
	 * propagation concept is introduced, only the existing MM1 queueing contention is
	 * bypassed for this path. The existing 15s failure cap is mirrored here (returns 0)
	 * purely for consistency with calculateMM1()'s failure semantics.
	 */
	double getLocalMigrationDelay(double dataSizeKB) {
		double dataSizeKb = dataSizeKB * 8; //KB to Kb, same convention as calculateMM1
		double result = dataSizeKb / MAN_BW + SimSettings.getInstance().getInternalLanDelay();
		return (result > 15) ? 0 : result;
	}

	public void updateMM1QueeuModel(){
		// Recompute Poisson means and average sizes based on window statistics.
		// Avoid division by zero by checking task counts.
		// Reset window accumulators for next period.
		double lastInterval = CloudSim.clock() - lastMM1QueueUpdateTime;
		lastMM1QueueUpdateTime = CloudSim.clock();
		
		if(numOfManTaskForDownload != 0){
			ManPoissonMeanForDownload = lastInterval / (numOfManTaskForDownload / (double)numberOfMobileDevices);
			avgManTaskOutputSize = totalManTaskOutputSize / numOfManTaskForDownload;
		}
		if(numOfManTaskForUpload != 0){
			ManPoissonMeanForUpload = lastInterval / (numOfManTaskForUpload / (double)numberOfMobileDevices);
			avgManTaskInputSize = totalManTaskInputSize / numOfManTaskForUpload;
		}

		// THESIS ADDITION 2026-08-25: permanent verification output for the mu-freeze fix
		// above. Before the fix this printed avgUpKB=2.7500 / avgDownKB=1.3750 on every
		// single tick, never drifting, at every device count. After the fix these should
		// vary tick to tick and converge toward the real traffic-weighted average implied
		// by the class mix (see applications.xml). Grep MM1STATE from run output.
		SimLogger.printLine(String.format(
				"MM1STATE t=%.1f devices=%d avgUpKB=%.4f avgDownKB=%.4f muUp=%.2f muDown=%.2f",
				CloudSim.clock(), numberOfMobileDevices, avgManTaskInputSize, avgManTaskOutputSize,
				MAN_BW / (avgManTaskInputSize * 8.0), MAN_BW / (avgManTaskOutputSize * 8.0)));
		
		totalManTaskOutputSize = 0;
		numOfManTaskForDownload = 0;
		totalManTaskInputSize = 0;
		numOfManTaskForUpload = 0;
	}
}
