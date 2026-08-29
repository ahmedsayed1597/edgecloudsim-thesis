#!/bin/bash
# Complete workflow: Run thesis device-count sweep + Python analysis + Excel export
#
# STEP 1: Runs Java MainApp for whatever range/policies are currently set in
#         scripts/thesis/config/default_config.properties, then verifies every
#         (policy, device count) combination actually produced its result files.
#
# STEP 2: Runs Python analyze_run.py to extract metrics from log files
#
# STEP 3: Runs Python export_to_excel.py to create results.xlsx
#
# WHY THIS SCRIPT EXISTS: MainApp cleans its output folder once at the START of each
# invocation, not per policy or per device count. So as long as orchestrator_policies
# lists BOTH policies (comma-separated) and the sweep runs as ONE invocation, every
# device count's files for both policies land in the same output folder together -
# nothing needs renaming. The failure mode this script guards against is running the
# policies in two SEPARATE invocations (e.g. CENTRALIZED alone, then changing the
# config and running DECENTRALIZED alone) - the second invocation's folder-clean wipes
# the first invocation's files, silently losing half the sweep. This script refuses to
# proceed if orchestrator_policies has fewer than 2 entries, specifically to catch that
# mistake before it happens rather than after.
#
# Usage:
#   scripts/thesis/run_sweep.sh
#
# Reads device range, policies, and scenario straight from
# scripts/thesis/config/default_config.properties - edit that file to change the sweep,
# not this script.
#
# Output:
#   - Log files: sim_results/thesis/ite1/
#   - Excel report: results.xlsx

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT_DIR"

CONFIG=scripts/thesis/config/default_config.properties
EDGE=scripts/thesis/config/edge_devices.xml
APPS=scripts/thesis/config/applications.xml
OUT=sim_results/thesis/ite1

get_prop() {
	grep -E "^$1=" "$CONFIG" | tail -1 | cut -d= -f2
}

POLICIES_RAW=$(get_prop orchestrator_policies)
SCENARIOS_RAW=$(get_prop simulation_scenarios)
MIN_DEV=$(get_prop min_number_of_mobile_devices)
MAX_DEV=$(get_prop max_number_of_mobile_devices)
STEP_DEV=$(get_prop mobile_device_counter_size)

IFS=',' read -ra POLICIES <<< "$POLICIES_RAW"
IFS=',' read -ra SCENARIOS <<< "$SCENARIOS_RAW"

if [ "${#POLICIES[@]}" -lt 2 ]; then
	echo "orchestrator_policies=$POLICIES_RAW has only ${#POLICIES[@]} polic$([ "${#POLICIES[@]}" -eq 1 ] && echo y || echo ies) set."
	echo "This script is for running CENTRALIZED and DECENTRALIZED together in one"
	echo "invocation so both land in $OUT without overwriting each other."
	echo "Set orchestrator_policies=CENTRALIZED,DECENTRALIZED in $CONFIG and re-run."
	exit 1
fi

echo "Sweep: devices $MIN_DEV..$MAX_DEV step $STEP_DEV | policies: $POLICIES_RAW | scenarios: $SCENARIOS_RAW"
echo
echo "================================================================================"
echo "STEP 1: Running Java simulation"
echo "================================================================================"

java -classpath "bin;lib/cloudsim-7.0.0-alpha.jar;lib/commons-math3-3.6.1.jar;lib/colt.jar" \
	edu.boun.edgecloudsim.applications.thesis.MainApp "$CONFIG" "$EDGE" "$APPS" "$OUT" 1

echo
echo "Verifying every (scenario, policy, device count) combination produced its SUCCESS log..."
MISSING=0
CHECKED=0
for scenario in "${SCENARIOS[@]}"; do
	for dev in $(seq "$MIN_DEV" "$STEP_DEV" "$MAX_DEV"); do
		for pol in "${POLICIES[@]}"; do
			f="$OUT/SIMRESULT_${scenario}_${pol}_${dev}DEVICES_SUCCESS.log"
			CHECKED=$((CHECKED + 1))
			if [ -f "$f" ]; then
				echo "  OK       $pol  ${dev} devices"
			else
				echo "  MISSING  $pol  ${dev} devices  (expected: $f)"
				MISSING=$((MISSING + 1))
			fi
		done
	done
done

echo
if [ "$MISSING" -eq 0 ]; then
	echo "SUCCESS: all $CHECKED result files present in $OUT."
	echo
	echo "================================================================================"
	echo "STEP 2: Analyzing results (extracting metrics)"
	echo "================================================================================"
	python scripts/thesis/analyze_run.py "$OUT"

	if [ $? -eq 0 ]; then
		echo
		echo "================================================================================"
		echo "STEP 3: Exporting to Excel (creating results.xlsx)"
		echo "================================================================================"
		python scripts/thesis/export_to_excel.py "$OUT"

		if [ $? -eq 0 ]; then
			echo
			echo "================================================================================"
			echo "COMPLETE!"
			echo "================================================================================"
			echo
			echo "Output:"
			echo "  - Log files:    $OUT/"
			echo "  - Excel report: results.xlsx"
			echo
			echo "Next: Open results.xlsx in Excel to view formatted tables"
		else
			echo "ERROR: Excel export failed"
			exit 1
		fi
	else
		echo "ERROR: Python analysis failed"
		exit 1
	fi
else
	echo "FAILED: $MISSING of $CHECKED result file(s) missing. Check the console output above for errors."
	exit 1
fi
