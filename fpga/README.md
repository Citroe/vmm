# FPGA bring-up: VmmController on Pynq-Z2

Isolated hardware bring-up harness for `VmmController` (the sequencing FSM
around `Vmm`; dim=32, inWidth=4, accWidth=13) on the Pynq-Z2
(xc7z020clg400-1). No AXI/RoCC/TileLink wrapper, no board I/O — stimulus and
capture go over JTAG via VIO (drive) and ILA (observe), exercising the
controller's `loadStart` / `weightRow` / `computeStart` / `busy` / `done`
handshake with nothing else in the datapath.

This drives `VmmController`, not the raw `Vmm` core: the 32-cycle weight-load
sequencing and the `aValid`-pulse / `yValid`-wait timing all live inside the
FSM now, so JTAG only has to pulse `loadStart`, hand over 32 rows, pulse
`computeStart`, and watch `done`. (`sim/tb_vmm.v` is still a core-only
testbench; `VmmControllerSpec` covers the FSM in chiseltest.)

Before wiring this up, run `fpga/synth_check.tcl`'s resource checkpoint
(see the root `README.md`) — it confirms the fully-spatial, no-DSP array
actually fits the Pynq-Z2's LUT budget before investing in the full BD/
bitstream flow below.

For a click-by-click GUI walkthrough of the whole project setup (create
project, hand-build the VIO/ILA IP and the PS7 block design, wrapper,
bitstream), see **`fpga/MANUAL_SETUP.md`**. The tcl-scripted path below is
the fast route; the manual doc is for when you want each step visible or
the scripts hit a Vivado-version difference.

## Build order

With a Vivado project open, targeting the Pynq-Z2:

```tcl
add_files generated/VmmController.v fpga/vmm_debug.v
source fpga/create_debug_ip.tcl
source fpga/build_bd.tcl
```

1. `add_files` first — `vmm_debug` has to be a known module reference
   before the block design can instantiate it. Add
   `generated/VmmController.v` (which already contains `Vmm` and `MacBank`),
   **not** `generated/Vmm.v` — adding both defines those two modules twice.
   Regenerate it with `sbt "runMain vmm.MainController --target-dir generated"`.
2. `create_debug_ip.tcl` — creates `vio_0`/`ila_0`, which `vmm_debug.v`
   instantiates directly as RTL (they are not cells in the block design
   canvas).
3. `build_bd.tcl` — creates the block design itself, wraps it
   (`system_wrapper`), and sets it as the top-level fileset.

After that, generate the bitstream as normal.

## Block design

Only two cells live in the BD canvas:

- **`ps7_0`** (`xilinx.com:ip:processing_system7`)
- **`vmm_debug_0`** — a Verilog module reference to `vmm_debug.v` (which
  instantiates `VmmController` + `vio_0` + `ila_0`)

wired clock-to-clock and reset-to-reset: `FCLK_CLK0` → `clk`,
`FCLK_RESET0_N` → `resetn`. No AXI connection between them — this harness
has no bus traffic.

### PS7 configuration

`apply_bd_automation` (board preset, `FIXED_IO`/`DDR` external, `Master
"Disable" Slave "Disable"`) is run first, then these are set explicitly on
top of it:

| Property | Value | Why |
|---|---|---|
| `PCW_FPGA0_PERIPHERAL_FREQMHZ` | 100 | `FCLK_CLK0`, enough for first bring-up |
| `PCW_USE_FABRIC_INTERRUPT` | 0 | nothing in this harness raises a PL→PS interrupt |
| `PCW_USE_M_AXI_GP0` | 0 | disables the port for real |
| `PCW_USE_M_AXI_GP1` | 0 | disables the port for real |

The `M_AXI_GP0`/`GP1` overrides are necessary because the automation
rule's "Master Disable" does **not** actually disable the AXI port on the
PS7 IP itself (confirmed on Vivado 2024.1) — it leaves `M_AXI_GP0_ACLK`
unconnected, which fails `validate_bd_design` ("clock pins are not
connected to a valid clock source"). If building the BD by hand instead of
via the script, re-open the PS7 customization dialog after automation and
uncheck GP0/GP1 under the AXI tab explicitly.

DDR/MIO are left at board-preset (or generic Zynq-7020, if no board files)
defaults — this harness never touches DDR.

**Board part caveat:** the script targets board part VLNV
`tul.com.tw:pynq-z2:part0:1.0`. Board file naming drifts across Vivado
versions/installs and may not be installed at all. The script checks with
`get_board_parts` first and skips the preset (rather than hard-erroring)
if it's missing — `apply_bd_automation` still succeeds without it.

### Known validation warning

`validate_bd_design` raises a CRITICAL WARNING: `FCLK_RESET0_N` is wired
straight into `vmm_debug`'s `resetn` with no synchronizer (an
asynchronous reset). Confirmed harmless for bring-up — still
synthesizes/routes fine. Swap in a "Processor System Reset" IP block
between them if this harness grows beyond a debug scratch build.

## Debug IP: VIO and ILA

Created by `create_debug_ip.tcl`, instantiated inside `vmm_debug.v` (RTL,
not BD cells). No version pinned on either — Vivado resolves to whatever
ships with the installed toolchain, since the `CONFIG.C_*` properties used
are stable across common versions.

### VIO (`vio_0`) — JTAG-driven stimulus

5 `probe_out` (JTAG → FPGA), 4 `probe_in` (FPGA → JTAG status readback):

| Probe | Width | Signal |
|---|---|---|
| `probe_out0` | 1 | `loadStart` |
| `probe_out1` | 128 | `weightRowData` (one 32-lane INT4 row) |
| `probe_out2` | 1 | `weightRowValid` |
| `probe_out3` | 1 | `computeStart` |
| `probe_out4` | 128 | `a` |
| `probe_in0` | 1 | `weightRow.ready` |
| `probe_in1` | 1 | `busy` |
| `probe_in2` | 1 | `done` |
| `probe_in3` | 1 | `error` |

All `probe_out*_INIT_VAL` are `0x0`. `weightRowData` and `a` are each 128
bits (32 lanes × 4b) — within VIO's 256-bit single-probe cap, one probe
apiece. There is no `weightRow` **address** probe anymore: the FSM
addresses rows itself (0, 1, … 31 in order). `vmm_debug.v` rising-edge-
detects `weightRowValid` into a single-cycle Decoupled beat, so one
high→low toggle feeds exactly one row; `loadStart` and `computeStart` are
edge-detected inside `VmmController` itself, so holding them high is
harmless.

### ILA (`ila_0`) — capture

5 probes, `C_DATA_DEPTH` 1024, `C_ADV_TRIGGER` enabled, `C_TRIGIN_EN`
false (no cascaded trigger-in):

| Probe | Width | Signal |
|---|---|---|
| `probe0` | 1 | `done` (trigger source) |
| `probe1` | 416 | packed `y` (32 × 13b lanes) |
| `probe2` | 1 | `busy` |
| `probe3` | 1 | `error` |
| `probe4` | 1 | `computeStart` |

`probe2`–`probe4` are along only to correlate a capture against FSM state,
not for triggering. `error` high in a capture means a compute pass was
started but the core never raised `yValid` — the FSM could not drive it.

Re-running the script after a config change deletes any existing
`vio_0`/`ila_0` first (`delete_ip`) — a stale IP otherwise blocks
re-creation with "already in use".

## Using it in Hardware Manager

### Getting debug cores to show up at all: PS7 must be initialized first

Programming the bitstream is not enough on its own. `dbg_hub` (the block
Vivado auto-inserts to bridge JTAG to the ILA/VIO cores) is clocked from
the same net as `vmm_debug`'s `clk` — i.e. `ps7_0/FCLK_CLK0`. On Zynq,
**FCLK0 does not start toggling just because the PL fabric is
programmed** — the PS side has to run its clock/PLL init sequence first
(normally done by an FSBL at boot). Skip that, and `dbg_hub` never gets a
running clock, so Hardware Manager reports "There are no debug cores" no
matter how correct the bitstream/probes pairing is.

Symptoms of this, in order encountered during bring-up:
- `Program Device` succeeds, but the Hardware window shows no
  `hw_ila`/`hw_vio` under the programmed device.
- Tcl Console shows `WARNING: [Labtools 27-3413] Dropping logic core ...
  since it cannot be found on the programmed device` — this specific one
  means the loaded `.ltx` probes file's cell UUIDs don't match what's
  actually in the currently-programmed bitstream (a stale bitstream/`.ltx`
  pairing — re-`Program Device`, and check the "Debug probes file" field
  in that dialog points at the `.ltx` sitting next to the `.bit` you just
  built, both from the same `impl_1` run).
- Once the `.ltx` pairing is fixed, still no cores: check the netlist has
  a debug hub at all —
  ```tcl
  get_cells -hier -filter {NAME =~ *dbg_hub*}
  ```
  (run against the *implemented* design). If this comes back non-empty
  (it should — `dbg_hub` insertion is a normal part of `route_design`),
  the remaining cause is almost always the FCLK0/PS7-init issue below,
  not a synthesis/implementation problem.

**Fix: initialize PS7 over JTAG using XSCT before touching the FPGA
device in Hardware Manager.** Vivado's *own* Hardware Manager Tcl console
does **not** work for this — the generated `ps7_init.tcl` calls `mrd`/
`mwr`/`configparams`, which are XSDB commands that only exist in **XSCT**
(ships with Vitis, not with Vivado alone). Sourcing `ps7_init.tcl`
directly in Vivado's console fails immediately with `invalid command name
"mrd"`.

Steps, using the ARM DAP target Vivado already shows in the Hardware
window (`arm_dap_0`, alongside the FPGA device `xc7z020_1`):

1. **Locate `ps7_init.tcl`** — generated from the PS7 config in your block
   design (named `design_1` if built via the GUI, `system` if built via
   `build_bd.tcl`). Look under:
   ```
   <project>.srcs/sources_1/bd/<bd_name>/ip/<bd_name>_processing_system7_0_0/ps7_init.tcl
   <project>.gen/sources_1/bd/<bd_name>/ip/<bd_name>_processing_system7_0_0/ps7_init.tcl
   ```
   (path drifts a bit by Vivado version — search the project folder if
   neither exists).

2. **Launch XSCT** and connect to the *same* hw_server Vivado's Hardware
   Manager already opened (don't let it spawn a second one — that
   contends for the same JTAG cable and can lock Vivado out of it). The
   port is visible in Vivado's Hardware Manager status/target string,
   default `3121`:
   ```tcl
   xsct
   connect -url tcp:127.0.0.1:3121
   targets
   ```

3. **Select the Cortex-A9 and reset it:**
   ```tcl
   targets -set -filter {name =~ "ARM*#0"}
   rst -processor
   ```

4. **Source and run PS7 init:**
   ```tcl
   source {<path-from-step-1>/ps7_init.tcl}
   ps7_init
   ps7_post_config
   ```
   (`ps7_post_config` may not be defined in every Vivado version's
   generated script — if it errors as undefined, ignore it, `ps7_init`
   alone is what brings up the clocks/PLLs/MIO config.)

5. **Back in Vivado's Hardware Manager** (same session, don't reconnect),
   switch to the ARM DAP device, refresh, then switch back and refresh
   the FPGA device:
   ```tcl
   current_hw_device [get_hw_devices arm_dap_0]
   refresh_hw_device -update_hw_probes false [current_hw_device]
   current_hw_device [get_hw_devices xc7z020_1]
   refresh_hw_device [current_hw_device]
   ```

`hw_ila_1`/`hw_vio_1` should now appear under the programmed device, and
the Tcl Console should log something like:
```
INFO: [Labtools 27-2302] Device xc7z020 (JTAG device index = 1) is programmed with a design that has 1 ILA core(s).
INFO: [Labtools 27-2302] Device xc7z020 (JTAG device index = 1) is programmed with a design that has 1 VIO core(s).
```

Booting the board's SD card normally (PYNQ/Linux boot already runs PS7
init as part of FSBL) is an alternative that avoids XSCT entirely, but
debugging *that* boot path (correct boot-mode jumper, correct image,
serial console) is its own rabbit hole — not needed just to unblock JTAG
debug cores.

### Driving a test

Handshake (see `src/main/scala/vmm/VmmController.scala`):

1. Toggle `vio_loadStart` high→low once. `VmmController` edge-detects it
   and enters its load state; `vio_busy` goes high, `vio_weightRow.ready`
   stays high for the whole 32-row sequence.
2. For each row, row 0 first: set `vio_weightRowData`, then toggle
   `vio_weightRowValid` high→low. `vmm_debug.v` edge-detects that into one
   Decoupled beat, so each toggle feeds exactly one row and the FSM
   advances its own row address. `vio_busy` drops when the 32nd row lands.
3. Set `vio_a`, toggle `vio_computeStart` high→low.
4. Arm the ILA on `probe0` (`done`) rising edge, single-shot trigger —
   **arm before step 3**, since `done` is a single-cycle pulse and a
   trigger armed afterward will simply miss it. `y` (`probe1`) is valid
   and held from that point until the next pass. `vio_error` / ILA
   `probe3` go high if the pass starts but `yValid` never arrives.

Doing the 32-row weight load by hand through the VIO GUI is tedious.
`fpga/run_hw_test.tcl` automates all of the above from the Hardware
Manager Tcl console — arms the ILA, drives a full weight matrix + vector
over JTAG via `commit_hw_vio`, waits for the trigger, and decodes/prints
all 32 `y` lanes from the captured data:

```tcl
source fpga/run_hw_test.tcl
set W [lrepeat 32 [lrepeat 32 0]]        ;# 32x32 zero matrix to start
lset W 0 [lreplace [lindex $W 0] 0 0 5]  ;# W[row 0][col 0] = 5
set A [lrepeat 32 0]
lset A 0 3                               ;# a[0] = 3
vmm_run $W $A                            ;# arms, drives, waits, prints y
```

Not yet validated against a live device end-to-end — the hw_probe names
it assumes (`vio_loadStart`, `dut_y`, etc., matching the net names in
`vmm_debug.v`) and the CSV column/radix format it parses out of
`write_hw_ila_data` can both vary by Vivado version. If it errors out on
first run, check `get_hw_probes -of_objects [get_hw_vios hw_vio_1]` /
`[get_hw_ilas hw_ila_1]` against the names configured at the top of the
script, and check the exported `vmm_capture.csv` matches what
`vmm_decode_y` expects to parse.

### Resolved: current dim=32/INT4 design meets timing at 100MHz

**Update (2026-08-27):** `fpga/timing_check.tcl` has now been run against
the current dim=32/INT4 design — WNS +0.327ns, WHS +0.134ns, 0 failing
endpoints out of 38,375, post-route signoff. See root `README.md`'s
"Timing closure: verified fixed at 100MHz" for the full numbers. The
section below describes the *previous* `MacBank` (dim=64, INT8,
time-multiplexed, DSP-mapped) and is kept as history/methodology, not as
a live caveat on the current design anymore.

**Controller wrapper — re-checked, meets timing at 100MHz. Latest run
2026-09-06 after the Phase-0 hardening** (load-stall timeout, mid-load
`loadStart` restart). Run against Vivado 2024.1 / `xc7z020clg400-1`, same
out-of-context `-max_dsp 0` flow, `generated/VmmController.v` (top
`VmmController`), full `synth_design` → `opt/place/route_design` →
`report_timing_summary`. Reports:
`fpga/synth_check_VmmController_utilization.rpt`,
`fpga/timing_check_VmmController_report.rpt`.

| Metric | `Vmm` core only | `VmmController` (pre-hardening) | `VmmController` (hardened) |
|---|---|---|---|
| WNS (setup) | +0.327 ns | +0.128 ns | **+0.393 ns** |
| WHS (hold) | +0.134 ns | +0.144 ns | +0.096 ns |
| Failing endpoints | 0 / 38,375 | 0 / 43,750 | 0 / 43,785 |
| Slice LUTs | 27,489 (51.7%) | 27,720 (52.1%) | 27,942 (52.5%) |
| Slice Registers | 23,432 (22.0%) | 23,990 (22.6%) | 24,003 (22.6%) |
| DSPs | 0 / 220 | 0 / 220 | 0 / 220 |

The FSM (with hardening) adds ~450 LUTs / ~570 FFs over the bare core and
~5,400 timing endpoints. Setup/hold are both met with zero failing
endpoints. The critical path is in the datapath adder tree, not the FSM,
so the WNS moves with P&R run-to-run variance (+0.128 → +0.393 here) more
than with FSM logic — treat any of these as "tight, ~0.1–0.4 ns", and
re-run `fpga/timing_check.tcl -tclargs VmmController
generated/VmmController.v` after *any* change to the controller or the
datapath before trusting a bitstream. (As with the core-only run, this is
OOC with `HD.CLK_SRC` unset, so clock-tree delay/skew is not modelled —
the on-chip number after full-device implementation with the real
`FCLK_CLK0` BUFG will differ somewhat.)

Regenerate + re-check:

```
sbt "runMain vmm.MainController --target-dir generated"
vivado -mode batch -source fpga/synth_check.tcl  -tclargs VmmController generated/VmmController.v
vivado -mode batch -source fpga/timing_check.tcl -tclargs VmmController generated/VmmController.v
```

Last checked `impl_1` run (prior design): **WNS -14.909 ns, TNS -8562.595
ns, 2576 failing endpoints** at the 100 MHz (`PCW_FPGA0_PERIPHERAL_FREQMHZ`)
target — "Timing constraints are not met." A bitstream still generates
and programs fine with failing timing (Vivado only warns), but results
captured from it can't be trusted as correct — at -14.9 ns slack against
a 10 ns period, the actual critical path was running at roughly 2.5x
budget, which risks metastable/unsettled values in the MAC datapath
rather than just being "a bit slow."

Likely cause (prior design): the time-multiplexed MAC bank's per-cycle
multiply+accumulate wasn't meeting 100 MHz, possibly because the
multiply/add chain wasn't landing in DSP48 slices (falling back to slower
LUT logic) or wasn't pipelined enough for a full 8×8 multiply + 22-bit
accumulate in one cycle. Never root-caused before the design changed.

Quick diagnostic if a fresh run of the current design needs unblocking
before a real fix: drop `PCW_FPGA0_PERIPHERAL_FREQMHZ` in `build_bd.tcl`
well below 100 (e.g. 20–25 MHz), regenerate, re-implement, and recheck the
timing report — if that closes timing, the datapath is functionally fine
and this becomes a speed/optimization problem (retiming, floorplanning)
rather than a correctness bug in the RTL itself.
