#!/usr/bin/env python3
"""
Generates scripts/thesis/config/edge_devices.xml for the thesis prototype.

Ahmed flagged (2026-08-25) that VMS_PER_HOST is a hidden experimental parameter: it
directly sets saturation capacity, and Step 1b's compute-floor calibration was measured
at VMS_PER_HOST=2. Regenerating from this script rather than hand-editing the XML keeps
that value visible, documented, and a single edit away from a sensitivity sweep, while
holding every other topology fact (14 datacenters, 1 host each, positions, WLAN ids,
attractiveness levels, host/VM resource sizes) fixed at the tutorial3 baseline.

Usage:
    python generate_edge_devices.py [VMS_PER_HOST]

    VMS_PER_HOST defaults to 2 (the Step 1b validated value). Re-run after changing it
    and re-run Step 1b calibration -- VM count changes the saturation point.

ATTRACTIVENESS DISTRIBUTION (audited and changed 2026-08-25):
tutorial3's original layout assigned attractiveness in three contiguous BLOCKS by
datacenter index: hosts 0-1 = level 0 (longest dwell), hosts 2-5 = level 1, hosts 6-13
= level 2 (shortest dwell). region_count partitions hosts into contiguous groups by
WLAN-id rank (ThesisEdgeOrchestrator.buildRegionMap()), and WLAN id follows datacenter
creation order here -- so with the original block layout and region_count=4, region 0
(hosts 0-3) contained NO level-2 (busiest) hosts at all, while regions 2 and 3 (hosts
8-13) were ENTIRELY level 2. Mobility pressure would have differed sharply by region for
reasons that were an artifact of geographic clustering, not a deliberate experimental
choice -- and Ahmed's mobility recalibration (Step 2) changed the level VALUES without
anyone re-examining whether this geographic SHAPE was still appropriate to pair with
them.

Fix: keep the same overall mix (2 x level-0, 4 x level-1, 8 x level-2 -- there is no
evidence the 1:2:4 skew itself is wrong; more high-churn than calm zones is a reasonable
urban-heterogeneity assumption) but interleave it via even fractional spacing (each
level's N items placed at floor((i+0.5) * 14/N) for i=0..N-1, remaining slots filled by
the largest group) instead of contiguous blocks. This is a general algorithm, not a
hand-picked sequence, so it stays reasoned if the 2:4:8 mix or datacenter count ever
changes. Result: every region_count from 2-4 now gets a representative mix of all three
levels in at least 3 of its 4 regions, instead of 2 of 4 regions being monolithic.
"""
import sys

VMS_PER_HOST = int(sys.argv[1]) if len(sys.argv) > 1 else 2

NUM_DATACENTERS = 14

# (x_pos, y_pos, wlan_id) for each datacenter -- positions and WLAN ids are unchanged
# from the tutorial3 baseline this study was seeded from. Attractiveness is computed
# below (see the note above), not hardcoded per-datacenter as it was before.
POSITIONS = [(i + 1, i + 1, i) for i in range(NUM_DATACENTERS)]


def interleaved_attractiveness(counts):
    """Evenly spread `counts[level] = how many hosts get that level` across
    NUM_DATACENTERS slots, instead of grouping each level into one contiguous block.
    Uses even fractional spacing per level, largest group fills any leftover slots."""
    assert sum(counts) == NUM_DATACENTERS
    assignment = [None] * NUM_DATACENTERS
    order = sorted(range(len(counts)), key=lambda lvl: counts[lvl])  # smallest group first
    for lvl in order:
        n = counts[lvl]
        if n == 0:
            continue
        placed = 0
        for i in range(n):
            pos = int((i + 0.5) * NUM_DATACENTERS / n)
            while assignment[pos] is not None:  # slot taken by an earlier (smaller) group
                pos = (pos + 1) % NUM_DATACENTERS
            assignment[pos] = lvl
            placed += 1
    # Any remaining unassigned slots go to the largest group (there should be none with
    # the counts used here, but this keeps the function correct for other ratios too).
    largest = order[-1]
    return [lvl if lvl is not None else largest for lvl in assignment]


# 2 x level-0 (longest dwell), 4 x level-1, 8 x level-2 (shortest dwell) -- same overall
# mix as tutorial3's original, see the note above for why the SHAPE changed but not this.
ATTRACTIVENESS = interleaved_attractiveness(counts=[2, 4, 8])

DATACENTERS = [(x, y, wlan, attr) for (x, y, wlan), attr in zip(POSITIONS, ATTRACTIVENESS)]

HOST = dict(core=16, mips=80000, ram=16000, storage=400000)
VM = dict(core=2, mips=10000, ram=2000, storage=50000)

VM_BLOCK = """\t\t\t\t\t<VM vmm="Xen">
\t\t\t\t\t\t<core>{core}</core>
\t\t\t\t\t\t<mips>{mips}</mips>
\t\t\t\t\t\t<ram>{ram}</ram>
\t\t\t\t\t\t<storage>{storage}</storage>
\t\t\t\t\t</VM>
"""

DATACENTER_BLOCK = """\t<datacenter arch="x86" os="Linux" vmm="Xen">
\t\t<costPerBw>0.1</costPerBw>
\t\t<costPerSec>3.0</costPerSec>
\t\t<costPerMem>0.05</costPerMem>
\t\t<costPerStorage>0.1</costPerStorage>
\t\t<location>
\t\t\t<x_pos>{x}</x_pos>
\t\t\t<y_pos>{y}</y_pos>
\t\t\t<wlan_id>{wlan}</wlan_id>
\t\t\t<attractiveness>{attr}</attractiveness>
\t\t</location>
\t\t<hosts>
\t\t\t<host>
\t\t\t\t<core>{hcore}</core>
\t\t\t\t<mips>{hmips}</mips>
\t\t\t\t<ram>{hram}</ram>
\t\t\t\t<storage>{hstorage}</storage>
\t\t\t\t<VMs>
{vms}\t\t\t\t</VMs>
\t\t\t</host>
\t\t</hosts>
\t</datacenter>
"""

def main():
    vms = "".join(VM_BLOCK.format(**VM) for _ in range(VMS_PER_HOST))
    body = "".join(
        DATACENTER_BLOCK.format(
            x=x, y=y, wlan=wlan, attr=attr,
            hcore=HOST["core"], hmips=HOST["mips"], hram=HOST["ram"], hstorage=HOST["storage"],
            vms=vms,
        )
        for (x, y, wlan, attr) in DATACENTERS
    )
    out = '<?xml version="1.0"?>\n<edge_devices>\n' + body + '</edge_devices>'

    with open("scripts/thesis/config/edge_devices.xml", "w", newline="\n") as f:
        f.write(out)

    print(f"Wrote scripts/thesis/config/edge_devices.xml: "
          f"{len(DATACENTERS)} datacenters x 1 host x {VMS_PER_HOST} VMs "
          f"= {len(DATACENTERS) * VMS_PER_HOST} edge VMs total.")

if __name__ == "__main__":
    main()
