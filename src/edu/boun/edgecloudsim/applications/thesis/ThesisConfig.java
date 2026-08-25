/*
 * Title:        EdgeCloudSim - Thesis Config
 *
 * Description:
 * Reads the thesis-specific experimental parameters that SimSettings does not know
 * about: region count for the decentralized orchestrator, and the two orchestrator
 * decision delays. Kept as a separate small loader rather than extending SimSettings so
 * that EdgeCloudSim core stays untouched -- this class just re-reads the same
 * .properties file MainApp already loads, ignoring the keys SimSettings itself uses.
 *
 * Both parameters are exactly the kind of thing Ahmed asked to be config-driven rather
 * than hardcoded, because he wants to sweep them later:
 *   - region_count: how many decentralized control regions partition the edge hosts.
 *     Directly controls how much visibility the decentralized orchestrator loses.
 *   - {centralized,decentralized}_decision_delay: simulated-time cost added before an
 *     orchestrator's decision takes effect. Read here in Step 2; wired into simulated
 *     time in Step 3 (ThesisMobileDeviceManager). Values agreed with Ahmed 2026-08-24:
 *     5 ms centralized / 0 ms decentralized, citing the Kubernetes scheduling survey's
 *     report of centralized scheduling delay running up to ~9x decentralized dispatch.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.thesis;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import edu.boun.edgecloudsim.utils.SimLogger;

public class ThesisConfig {
	private final int regionCount;
	private final double centralizedDecisionDelay;
	private final double decentralizedDecisionDelay;

	public ThesisConfig(String propertiesFile) {
		Properties prop = new Properties();
		try (InputStream input = new FileInputStream(propertiesFile)) {
			prop.load(input);
		} catch (IOException e) {
			SimLogger.printLine("Thesis config cannot be read from '" + propertiesFile
					+ "'! Terminating simulation...");
			e.printStackTrace();
			System.exit(1);
		}

		regionCount = Integer.parseInt(prop.getProperty("region_count", "4"));
		centralizedDecisionDelay = Double.parseDouble(prop.getProperty("centralized_decision_delay", "0.005"));
		decentralizedDecisionDelay = Double.parseDouble(prop.getProperty("decentralized_decision_delay", "0"));

		if (regionCount < 1) {
			SimLogger.printLine("region_count must be >= 1! Terminating simulation...");
			System.exit(1);
		}
	}

	/** Number of decentralized control regions the edge hosts are partitioned into. */
	public int getRegionCount() {
		return regionCount;
	}

	/** Simulated-time cost (seconds) of a CENTRALIZED placement decision. */
	public double getCentralizedDecisionDelay() {
		return centralizedDecisionDelay;
	}

	/** Simulated-time cost (seconds) of a DECENTRALIZED placement decision. */
	public double getDecentralizedDecisionDelay() {
		return decentralizedDecisionDelay;
	}
}
