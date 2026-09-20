# Manual Vivado setup — `Vmm` + `VmmController` on the Pynq-Z2

Click-by-click walkthrough for building the JTAG bring-up project by hand in
the Vivado GUI (2024.1), instead of sourcing `fpga/create_debug_ip.tcl` +
`fpga/build_bd.tcl`. Use this when you want to see/understand each step, or
when the automation scripts trip over a Vivado-version difference.

What you end up with: `VmmController` (the sequencing FSM) wrapping the `Vmm`
core, driven and observed over JTAG through a VIO + ILA, clocked by the Zynq
PS's `FCLK_CLK0`. No AXI, no board I/O.

- **Target part:** `xc7z020clg400-1`
- **Board (optional):** Pynq-Z2, VLNV `tul.com.tw:pynq-z2:part0:1.0`
- **Clock:** `FCLK_CLK0` @ 100 MHz
- Related docs: `fpga/README.md` (how the harness works + Hardware-Manager
  troubleshooting), root `README.md` (the core itself).

---

## 0. Generate the RTL

From the repo root:

```
sbt "runMain vmm.MainController --target-dir generated"
```

This writes **`generated/VmmController.v`**, which contains `VmmController`,
`Vmm`, and `MacBank` in one file. Use *only* this file in the project — do
**not** also add `generated/Vmm.v`, or `Vmm`/`MacBank` get defined twice.

(`generated/Vmm.v` from `sbt "runMain vmm.Main …"` is for the core-only
paths: `sim/tb_vmm.v` and the no-arg resource/timing scripts.)

---

## 1. Create the project

1. **File → Project → New…** → Next.
2. **Project name:** e.g. `vmm_hw`. Pick a location *outside* the repo (keeps
   the ~GB of Vivado output out of git). → Next.
3. **Project type:** RTL Project. Leave *"Do not specify sources at this
   time"* **unchecked** so you can add sources now. → Next.
4. **Add Sources:**
   - **Add Files** → `generated/VmmController.v`
   - **Add Files** → `fpga/vmm_debug.v`
   - Target language: **Verilog**. "Copy sources into project" is your call
     (checked = safe snapshot; unchecked = tracks the file on disk). → Next.
5. **Add Constraints:** skip (this harness has no pin constraints — all I/O
   is JTAG). → Next.
6. **Default Part:**
   - If you have Pynq-Z2 board files: **Boards** tab → select *Pynq-Z2*.
   - Otherwise: **Parts** tab → `xc7z020clg400-1`.
   - → Next → Finish.

After the project opens, in the **Sources** pane you should see `vmm_debug`
with `VmmController` nested under it, and `Vmm` → `MacBank` under that.
`vio_0` / `ila_0` show as missing black boxes until step 2.

---

## 2. Create the VIO and ILA debug cores

`vmm_debug.v` instantiates `vio_0` and `ila_0` directly as RTL (they are not
cells on a block-design canvas), so they must exist as IP with matching
names and probe widths.

### 2a. VIO (`vio_0`)

1. **Window → IP Catalog** → search **VIO** → double-click *VIO (Virtual
   Input/Output)*.
2. **Component Name:** `vio_0` (exactly).
3. **General Options** tab:
   - Input Probe Count: **4**
   - Output Probe Count: **5**
   - "Enable Input Probe Activity Detectors": optional.
4. **PROBE_IN Ports** tab — widths:

   | Probe | Width | Signal it reads back |
   |---|---|---|
   | `PROBE_IN0` | 1 | `weightRow.ready` |
   | `PROBE_IN1` | 1 | `busy` |
   | `PROBE_IN2` | 1 | `done` |
   | `PROBE_IN3` | 1 | `error` |

5. **PROBE_OUT Ports** tab — widths and initial values (all INIT `0x0`):

   | Probe | Width | Signal it drives |
   |---|---|---|
   | `PROBE_OUT0` | 1 | `loadStart` |
   | `PROBE_OUT1` | 128 | `weightRowData` (one 32-lane INT4 row) |
   | `PROBE_OUT2` | 1 | `weightRowValid` |
   | `PROBE_OUT3` | 1 | `computeStart` |
   | `PROBE_OUT4` | 128 | `a` |

6. **OK** → **Generate** (Out-of-context per IP is fine) → wait for
   synthesis of the OOC module to finish.

### 2b. ILA (`ila_0`)

1. **IP Catalog** → search **ILA** → double-click *ILA (Integrated Logic
   Analyzer)*.
2. **Component Name:** `ila_0`.
3. **General Options** tab:
   - Number of Probes: **5**
   - Sample Data Depth: **1024**
   - "Advanced Trigger": **checked** (`C_ADV_TRIGGER`)
   - "Enable Trigger In/Out Ports" (`C_TRIGIN_EN`): **unchecked**
   - Leave "Same Number of Comparators for All Probe Ports" default.
4. **Probe_Ports(0..4)** tab — widths:

   | Probe | Width | Signal | Role |
   |---|---|---|---|
   | `PROBE0` | 1 | `done` | trigger source |
   | `PROBE1` | 416 | `y` (32 × 13b, packed) | data |
   | `PROBE2` | 1 | `busy` | correlation |
   | `PROBE3` | 1 | `error` | correlation |
   | `PROBE4` | 1 | `computeStart` | correlation |

5. **OK** → **Generate**.

> If you ever change a probe width, delete the IP (**right-click → Delete**
> in Sources/IP Sources) and recreate it — Vivado blocks re-customization of
> a name that's "already in use".

After both generate, the black boxes under `vmm_debug` in **Sources**
resolve.

---

## 3. Block design: Zynq PS for the clock

`vmm_debug` needs a running `clk`/`resetn`. On Zynq the PL has no standalone
oscillator, so these come from the PS's `FCLK_CLK0` / `FCLK_RESET0_N`.

1. **Flow Navigator → IP Integrator → Create Block Design.**
   - **Design name:** `system` (the automation scripts and `fpga/README.md`
     assume this name; the GUI default is `design_1` — if you keep that,
     remember to substitute it in the `ps7_init.tcl` path later).
   - → OK.
2. In the empty diagram: **+ (Add IP)** → search **ZYNQ7 Processing
   System** → add it.
3. Green **"Run Block Automation"** bar at the top → click it.
   - **Apply Board Preset:** checked *if* you have Pynq-Z2 board files;
     otherwise leave unchecked (generic Zynq-7020 defaults are fine — this
     harness never touches DDR/MIO).
   - **Make External:** `FIXED_IO`, `DDR`.
   - Cross-trigger / Master / Slave AXI: **Disable**.
   - → OK.
4. **Configure the PS** — double-click the `processing_system7_0` block:
   - **Clock Configuration → PL Fabric Clocks:** enable `FCLK_CLK0`, set it
     to **100 MHz** (`PCW_FPGA0_PERIPHERAL_FREQMHZ`).
   - **PS-PL Configuration → General → Enable Clock Resets:** make sure
     `FCLK_RESET0_N` is enabled.
   - **PS-PL Configuration → AXI Non Secure Enablement → GP Master AXI
     Interface:** **uncheck `M AXI GP0` and `M AXI GP1`.**
     This matters: Block Automation's "Master: Disable" does *not* actually
     turn the ports off on the PS7 IP (confirmed on 2024.1) — it just leaves
     `M_AXI_GP0_ACLK` unconnected, and `Validate Design` then fails with
     *"clock pins are not connected to a valid clock source."*
   - **Interrupts:** leave fabric interrupts off (`PCW_USE_FABRIC_INTERRUPT
     = 0`) — nothing here raises one.
   - → OK.
5. **Add `vmm_debug`:** right-click the canvas → **Add Module…** → select
   `vmm_debug` → OK. It appears as `vmm_debug_0`.
   (If "Add Module" is greyed out, run synthesis's *Elaborate* once, or make
   sure `vmm_debug.v` is under **Design Sources**, not Simulation.)
6. **Wire it up** (drag pin-to-pin):
   - `processing_system7_0/FCLK_CLK0` → `vmm_debug_0/clk`
   - `processing_system7_0/FCLK_RESET0_N` → `vmm_debug_0/resetn`
   - No AXI connection. Nothing else.
7. **Regenerate Layout** (toolbar) for readability, then **Validate Design**
   (F6).
   - Expect a **CRITICAL WARNING**: `FCLK_RESET0_N` drives `resetn` with no
     synchronizer (async reset). Confirmed harmless for bring-up — it still
     synthesizes/routes. Add a *Processor System Reset* IP between them if
     this harness ever grows past a scratch build.
   - Any *error* (not warning) here is almost always the GP0/GP1 checkbox
     from step 4 — go back and uncheck them.
8. **Save** (Ctrl-S).

---

## 4. HDL wrapper + set top

1. **Sources** pane → right-click `system.bd` → **Create HDL Wrapper…**
   - "Let Vivado manage wrapper and auto-update" → OK.
   - This creates `system_wrapper.v`.
2. Right-click **`system_wrapper`** → **Set as Top**. It should go bold.
   (`vmm_debug` is now instantiated *inside* the BD wrapper, not the project
   top.)

---

## 5. Synthesize, implement, generate bitstream

1. **Flow Navigator → Run Synthesis** → OK. Wait.
2. On completion → **Run Implementation** → OK. Wait.
3. Check timing: **Implementation → Open Implemented Design → Reports →
   Timing → Report Timing Summary** (or the *Design Timing Summary* in the
   results). **WNS must be ≥ 0.** For reference, the OOC signoff on this
   design is WNS ≈ **+0.128 ns** at 100 MHz (see root `README.md`) — a
   full-device number near or slightly above that is expected; a negative
   WNS means something regressed, don't program it.
4. **Generate Bitstream** → OK.
5. Note the two files under
   `<project>.runs/impl_1/`:
   - `system_wrapper.bit`
   - `system_wrapper.ltx`  ← the debug-probes file; it must be programmed
     *together with* the `.bit` from the *same* run or the ILA/VIO cores
     won't bind.

---

## 6. Program the board + bring up the debug cores

This is the fiddly part on a bare PL bring-up (no SD/OS boot): `FCLK_CLK0`
does not toggle until the PS runs its clock/PLL init, and the `dbg_hub` is
clocked from `FCLK_CLK0`, so with no PS init the Hardware Manager reports
*"There are no debug cores"* no matter how good the bitstream is.

The full procedure — XSCT `ps7_init` over JTAG, the exact `ps7_init.tcl`
path, the `refresh_hw_device` dance — is written out in
**`fpga/README.md` → "Using it in Hardware Manager"**. In brief:

1. **Open Hardware Manager** → **Open Target → Auto Connect**.
2. **Program Device** with `system_wrapper.bit`; in that dialog set **Debug
   probes file** to the matching `system_wrapper.ltx`.
3. If no `hw_ila_1` / `hw_vio_1` appear: launch **XSCT** (from Vitis),
   `connect` to the same hw_server (`tcp:127.0.0.1:3121`), select the
   Cortex-A9, `rst -processor`, `source …/ps7_init.tcl`, `ps7_init`. Then
   back in Vivado's Hardware Manager, `refresh_hw_device` the ARM DAP then
   the FPGA device. Cores should now enumerate.

---

## 7. Drive a test

### Option A — automated (recommended)

From the **Hardware Manager Tcl Console**:

```tcl
source fpga/run_hw_test.tcl
set W [lrepeat 32 [lrepeat 32 0]]        ;# 32x32 zero matrix
lset W 0 [lreplace [lindex $W 0] 0 0 5]  ;# W[row 0][col 0] = 5
set A [lrepeat 32 0]
lset A 0 3                               ;# a[0] = 3
vmm_run $W $A                            ;# arms ILA, drives, waits, prints y
```

Expected: `y[0] = 15` (`a[0]*W[0][0] = 3*5`), all other lanes `0`.
`vmm_run` arms the ILA on `done`, pulses `loadStart`, streams the 32 rows,
pulses `computeStart`, waits for the trigger, and decodes all 32 `y` lanes
from the capture. `vmm_status` prints the VIO readbacks
(`weightRow.ready` / `busy` / `done` / `error`) if you want to watch
progress.

### Option B — by hand in the VIO / ILA dashboards

1. **Hardware → hw_vio_1 → open the dashboard.** Add all probes
   (`vio_loadStart`, `vio_weightRowData`, `vio_weightRowValid`,
   `vio_computeStart`, `vio_a`, and read-backs `dut_weightRowReady`,
   `dut_busy`, `dut_done`, `dut_error`). Set `weightRowData` / `a` radix to
   **Hex**.
2. **hw_ila_1** → set the trigger: `dut_done == 1'b1`, trigger mode
   *Basic*, single-shot. **Arm** it (`Run Trigger`) — do this **before**
   step 6, `done` is a one-cycle pulse.
3. **Load weights.** For each row 0…31:
   - set `vio_weightRowData` = the 128-bit packed row (lane 0 in the low
     nibble; a zero matrix is just `0`),
   - toggle `vio_weightRowValid` **1 → 0** (each 1→0 = exactly one row; the
     FSM addresses rows itself). Do the first `loadStart` **1 → 0** toggle
     once, before the first row.
   - watch `dut_busy` = 1 during the load, back to 0 after row 31.
4. **Compute.** Set `vio_a` = packed activation vector, toggle
   `vio_computeStart` **1 → 0**.
5. The ILA triggers on `done`. **Hardware → hw_ila_1 → Waveform:** read
   `dut_y` (416 bits = 32 × 13b signed, lane 0 in the low 13 bits). `y`
   holds until the next pass.
6. `dut_error` = 1 means the pass started but the core never raised
   `yValid` — the FSM could not drive it (should not happen on a
   timing-clean build).

---

## Signal reference

`vmm_debug.v` net names ↔ `VmmController` ports (`src/main/scala/vmm/VmmController.scala`):

| `vmm_debug.v` net | `VmmController` port | Dir | Notes |
|---|---|---|---|
| `vio_loadStart` | `io_loadStart` | in | rising-edge-detected inside the FSM; holding it high is harmless |
| `vio_weightRowData` | `io_weightRow_bits_0..31` | in | 128b = 32 × INT4, lane 0 = `[3:0]` |
| `vio_weightRowValid` | `io_weightRow_valid` | in | **edge-detected in `vmm_debug.v`** into one Decoupled beat = one row |
| `dut_weightRowReady` | `io_weightRow_ready` | out | high through the whole 32-row load |
| `vio_computeStart` | `io_computeStart` | in | rising-edge-detected inside the FSM |
| `vio_a` | `io_a_0..31` | in | 128b = 32 × INT4 |
| `dut_busy` | `io_busy` | out | high from a start strobe until the op finishes |
| `dut_done` | `io_done` | out | one-cycle pulse; ILA trigger |
| `dut_error` | `io_error` | out | sticky; set on a compute pass that never sees `yValid` |
| `dut_y` | `io_y_0..31` | out | 416b = 32 × 13b signed; holds until next pass |

There is **no** `weightRow` address probe — the FSM generates the row
address (0, 1, … 31) itself.

---

## Behavioral simulation (optional, no hardware)

Two self-checking Verilog testbenches, both plain Verilog-2001 for xsim,
both printing `PASS: …` / `FAIL: …` at `$finish`:

| Testbench | DUT | Add these sim sources | Sim top |
|---|---|---|---|
| `sim/tb_vmm.v` | `Vmm` core | `sim/tb_vmm.v` + `generated/Vmm.v` | `tb_vmm` |
| `sim/tb_vmm_controller.v` | `VmmController` (FSM + core) | `sim/tb_vmm_controller.v` + `generated/VmmController.v` | `tb_vmm_controller` |

Use `generated/Vmm.v` with `tb_vmm` and `generated/VmmController.v` with
`tb_vmm_controller` — never both `.v` files in the same sim set (`Vmm` /
`MacBank` would be defined twice).

1. Project → **Add Sources → Add or create simulation sources** → the row's
   two files.
2. Set the row's module as the simulation top.
3. **Flow Navigator → Run Simulation → Run Behavioral Simulation.**

`tb_vmm_controller` drives the full handshake — pulse `loadStart`, stream
32 weight rows, pulse `computeStart`, wait for `done` — and checks `y`
against a golden model plus that `error` stays low. The `VmmController`
error/timeout paths (load stall, mid-load restart, compute timeout) are
covered by the chiseltest suite only (`sbt "testOnly vmm.VmmControllerSpec"`).
