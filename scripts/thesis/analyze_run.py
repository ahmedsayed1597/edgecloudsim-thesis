#!/usr/bin/env python3
"""
Summarizes a thesis MainApp output folder into the standard reporting table:
device count, policy, per-class compliance, failure %, MAN-hop %, and (2026-08-25
addition) a failure-reason breakdown pulled from SimLogger's own aggregated counters.

Reads:
  - the deep per-task logs (*_SUCCESS.log / *_FAIL.log) for compliance and MAN-hop %.
  - the per-class GENERIC.log files (*_<APPNAME>_GENERIC.log) for the failure-reason
    breakdown (VM capacity / WLAN coverage / mobility / LAN+MAN+WAN+GSM bandwidth),
    since the deep FAIL.log's per-task reason code does not distinguish which network
    segment a bandwidth failure happened on, but SimLogger already tracks that
    per-class in the arrays written to GENERIC.log (verified against SimLogger.java's
    genericResult1/genericResult5 field layout directly, not assumed).

Does not run any simulation - point it at an output folder that already exists (e.g.
sim_results/thesis/ite1) and it just parses text files.

Deadlines and task-type-to-class mapping are read from applications.xml, not hardcoded.

Usage:
    python analyze_run.py <output_folder> [applications_xml]

    applications_xml defaults to scripts/thesis/config/applications.xml.

Edge/Cloud/Mobile utilization is NOT in these logs - it only appears in the console
summary line "average server utilization Edge/Cloud/Mobile: ...". Paste that line
alongside this script's table when reporting back.
"""
import sys
import glob
import os
import re
import xml.etree.ElementTree as ET


def load_deadlines(apps_xml_path):
    tree = ET.parse(apps_xml_path)
    deadlines = {}
    names = {}
    for i, app in enumerate(tree.getroot().findall("application")):
        names[i] = app.get("name")
        el = app.find("max_delay_requirement")
        deadlines[i] = float(el.text) * 1000 if el is not None else None  # seconds -> ms
    return deadlines, names


def find_runs(output_folder):
    """Groups *_SUCCESS.log / *_FAIL.log pairs by (scenario_policy, deviceCount)."""
    runs = {}
    for succ in glob.glob(os.path.join(output_folder, "SIMRESULT_*_*DEVICES_SUCCESS.log")):
        base = succ[: -len("_SUCCESS.log")]
        m = re.match(r".*SIMRESULT_(.+)_(\d+)DEVICES$", base)
        if not m:
            continue
        scenario_policy, devices = m.group(1), int(m.group(2))
        fail = base + "_FAIL.log"
        runs[(scenario_policy, devices)] = (succ, fail if os.path.exists(fail) else None, base)
    return runs


def parse_generic_failure_reasons(base_path, class_name):
    """Reads <base_path>_<class_name>_GENERIC.log and returns the failure-reason
    breakdown SimLogger itself computed for this class. Returns None if the file
    doesn't exist (e.g. deep_file_log_enabled was on but this specific combination
    produced no file for some reason)."""
    path = f"{base_path}_{class_name}_GENERIC.log"
    if not os.path.exists(path):
        return None
    with open(path) as f:
        lines = [ln.strip() for ln in f if not ln.startswith("#") and ln.strip()]
    if len(lines) < 5:
        return None
    r1 = lines[0].split(";")  # genericResult1
    r5 = lines[4].split(";")  # genericResult5
    return dict(
        completed=int(r1[0]), failed_total=int(r1[1]), uncompleted=int(r1[2]),
        failed_bw=int(r1[3]),
        vm_capacity=int(r1[9]), mobility=int(r1[10]), wlan_coverage=int(r1[13]),
        bw_lan=int(r5[4]), bw_man=int(r5[5]), bw_wan=int(r5[6]), bw_gsm=int(r5[7]),
    )


def analyze(succ_path, fail_path, deadlines):
    per_class = {}  # taskType -> {times: [...], man_hop: int, n: int}
    fail_counts = {}
    total = 0

    with open(succ_path) as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.strip().split(";")
            task_type = int(parts[6])
            start, end = float(parts[10]), float(parts[11])
            man_delay = float(parts[14]) if len(parts) > 14 else 0.0
            d = per_class.setdefault(task_type, {"times": [], "man_hop": 0, "n": 0})
            d["times"].append((end - start) * 1000.0)  # -> ms
            d["n"] += 1
            if man_delay > 0:
                d["man_hop"] += 1
            total += 1

    if fail_path:
        with open(fail_path) as f:
            for line in f:
                if line.startswith("#"):
                    continue
                parts = line.strip().split(";")
                task_type = int(parts[6])
                fail_counts[task_type] = fail_counts.get(task_type, 0) + 1
                total += 1

    rows = []
    for t in sorted(set(list(per_class.keys()) + list(fail_counts.keys()))):
        d = per_class.get(t, {"times": [], "man_hop": 0, "n": 0})
        nf = fail_counts.get(t, 0)
        n_total_class = d["n"] + nf
        deadline = deadlines.get(t)
        within = None
        if deadline is not None and n_total_class > 0:
            within = 100.0 * sum(1 for x in d["times"] if x <= deadline) / n_total_class

        # FIX 2026-08-25: this used to be n_total_class / total (this class's share of
        # the WHOLE run's tasks across both classes - a population-share number, not a
        # failure rate). Since URLLC generates ~10x more tasks than eMBB, that produced
        # a number that just tracked task-volume proportion (~90%/~10%) regardless of
        # actual failures - which is exactly the "flat ~9-11% eMBB / climbing 89-92%
        # URLLC" pattern that didn't look like a failure rate, because it wasn't one.
        # Correct formula: this class's own failures over this class's own tasks.
        failed_pct = 100.0 * nf / n_total_class if n_total_class else None

        # MAN-hop % is computed only over SUCCESSFUL tasks (man_delay is only present
        # in the SUCCESS log). Two things to watch for when reading this column:
        #   1. If n_ok is 0 or very small, this ratio is undefined/noisy - reported as
        #      None (not 0.0%) so an empty-denominator case can't be misread as "no MAN
        #      hops occurred". Check n_ok before trusting this number.
        #   2. Even with n_ok > 0, this is a SURVIVOR-only statistic: if MAN-relayed
        #      tasks are more likely to fail (longer path, more chances to blow a
        #      mobility or bandwidth check), then as the system saturates, survivors
        #      skew toward non-MAN-hop tasks and this ratio declines for reasons that
        #      have nothing to do with routing behavior changing. Cross-check against
        #      the failure-reason breakdown (bw_man, mobility, etc. below) to tell
        #      "MAN routing stopped happening" apart from "MAN-routed tasks stopped
        #      surviving to be counted here".
        man_pct = 100.0 * d["man_hop"] / d["n"] if d["n"] else None

        rows.append(dict(task_type=t, n_ok=d["n"], n_fail=nf, n_total=n_total_class,
                          within=within, man_pct=man_pct, failed_pct=failed_pct))
    return rows, total


def fmt_pct(x):
    return f"{x:.1f}%" if x is not None else "n/a"


# Only these two policy names exist in this codebase (ThesisEdgeOrchestrator.CENTRALIZED
# / .DECENTRALIZED) - matching against them directly is simpler and more robust than
# trying to guess where "scenario" ends and "policy" begins in a string like
# "DEFAULT_SCENARIO_CENTRALIZED", since scenario names are free-form.
POLICY_NAMES = ["DECENTRALIZED", "CENTRALIZED"]  # DECENTRALIZED first: it's a suffix
                                                   # of nothing else here, but check the
                                                   # longer name first on principle.


def split_scenario_policy(scenario_policy):
    for pol in POLICY_NAMES:
        suffix = "_" + pol
        if scenario_policy.endswith(suffix):
            return scenario_policy[: -len(suffix)], pol
    return scenario_policy, None  # unrecognized policy name - caller should handle


def print_paired_compliance(runs, deadlines, names):
    # (scenario, devices, class) -> {policy: {within, n_ok, n_fail, man_pct}}
    paired = {}
    for (scenario_policy, devices), (succ, fail, base) in runs.items():
        scenario, policy = split_scenario_policy(scenario_policy)
        if policy is None:
            continue
        rows, _ = analyze(succ, fail, deadlines)
        for r in rows:
            cls = names.get(r["task_type"], f"type{r['task_type']}")
            key = (scenario, devices, cls)
            paired.setdefault(key, {})[policy] = r

    print()
    print("Paired comparison: CENTRALIZED vs DECENTRALIZED")
    print(f"{'devices':>8} {'class':<12} {'CENTR within%':>14} {'DECENTR within%':>17} "
          f"{'gap(D-C)':>10} {'CENTR MANhop%':>14} {'DECENTR MANhop%':>17}")
    print("-" * 100)
    for (scenario, devices, cls) in sorted(paired.keys(), key=lambda k: (k[1], k[2])):
        both = paired[(scenario, devices, cls)]
        c = both.get("CENTRALIZED")
        d = both.get("DECENTRALIZED")
        c_within = c["within"] if c else None
        d_within = d["within"] if d else None
        gap = (d_within - c_within) if (c_within is not None and d_within is not None) else None
        gap_str = f"{gap:+.1f}pt" if gap is not None else "n/a"
        print(f"{devices:>8} {cls:<12} {fmt_pct(c_within):>14} {fmt_pct(d_within):>17} "
              f"{gap_str:>10} {fmt_pct(c['man_pct'] if c else None):>14} "
              f"{fmt_pct(d['man_pct'] if d else None):>17}")


FAILURE_REASON_LABELS = ["vmCap", "wlanCov", "mobility", "bwLAN", "bwMAN", "bwWAN", "bwGSM"]


def dominant_reason(fr):
    if fr is None:
        return "n/a", 0
    values = [fr["vm_capacity"], fr["wlan_coverage"], fr["mobility"],
              fr["bw_lan"], fr["bw_man"], fr["bw_wan"], fr["bw_gsm"]]
    if sum(values) == 0:
        return "none", 0
    idx = max(range(len(values)), key=lambda i: values[i])
    return FAILURE_REASON_LABELS[idx], values[idx]


def print_paired_failure_reasons(runs, names):
    # (scenario, devices, class) -> {policy: base_path}
    paired = {}
    for (scenario_policy, devices), (succ, fail, base) in runs.items():
        scenario, policy = split_scenario_policy(scenario_policy)
        if policy is None:
            continue
        paired.setdefault((scenario, devices), {})[policy] = base

    print()
    print("Paired failure-reason breakdown (dominant reason + its count, per policy):")
    print(f"{'devices':>8} {'class':<12} {'CENTR dominant':<18} {'DECENTR dominant':<18}")
    print("-" * 100)
    for (scenario, devices) in sorted(paired.keys(), key=lambda k: k[1]):
        bases = paired[(scenario, devices)]
        for t, cls in sorted(names.items()):
            c_fr = parse_generic_failure_reasons(bases["CENTRALIZED"], cls) if "CENTRALIZED" in bases else None
            d_fr = parse_generic_failure_reasons(bases["DECENTRALIZED"], cls) if "DECENTRALIZED" in bases else None
            c_label, c_count = dominant_reason(c_fr)
            d_label, d_count = dominant_reason(d_fr)
            print(f"{devices:>8} {cls:<12} {c_label + ' (' + str(c_count) + ')':<18} "
                  f"{d_label + ' (' + str(d_count) + ')':<18}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    output_folder = sys.argv[1]
    apps_xml = sys.argv[2] if len(sys.argv) > 2 else "scripts/thesis/config/applications.xml"

    deadlines, names = load_deadlines(apps_xml)
    runs = find_runs(output_folder)

    if not runs:
        print(f"No SIMRESULT_*_*DEVICES_SUCCESS.log files found in {output_folder}")
        sys.exit(1)

    print(f"{'devices':>8} {'policy':<26} {'class':<12} {'n_ok':>8} {'n_fail':>7} "
          f"{'within%':>9} {'MANhop%':>9} {'ownFail%':>10}")
    print("-" * 96)
    for (scenario_policy, devices) in sorted(runs.keys(), key=lambda k: (k[1], k[0])):
        succ, fail, base = runs[(scenario_policy, devices)]
        rows, total = analyze(succ, fail, deadlines)
        for r in rows:
            cls = names.get(r["task_type"], f"type{r['task_type']}")
            print(f"{devices:>8} {scenario_policy:<26} {cls:<12} {r['n_ok']:>8} {r['n_fail']:>7} "
                  f"{fmt_pct(r['within']):>9} {fmt_pct(r['man_pct']):>9} {fmt_pct(r['failed_pct']):>10}")

    print()
    print("Failure-reason breakdown (from SimLogger's own per-class GENERIC.log counters):")
    print(f"{'devices':>8} {'policy':<26} {'class':<12} {'vmCap':>6} {'wlanCov':>8} "
          f"{'mobility':>9} {'bwLAN':>6} {'bwMAN':>6} {'bwWAN':>6} {'bwGSM':>6}")
    print("-" * 96)
    for (scenario_policy, devices) in sorted(runs.keys(), key=lambda k: (k[1], k[0])):
        _, _, base = runs[(scenario_policy, devices)]
        for t, cls in sorted(names.items()):
            fr = parse_generic_failure_reasons(base, cls)
            if fr is None:
                continue
            print(f"{devices:>8} {scenario_policy:<26} {cls:<12} {fr['vm_capacity']:>6} "
                  f"{fr['wlan_coverage']:>8} {fr['mobility']:>9} {fr['bw_lan']:>6} "
                  f"{fr['bw_man']:>6} {fr['bw_wan']:>6} {fr['bw_gsm']:>6}")

    print_paired_compliance(runs, deadlines, names)
    print_paired_failure_reasons(runs, names)


if __name__ == "__main__":
    main()
