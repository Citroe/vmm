# vmm

A 32x32 INT4 weight-stationary, fully-spatial Vector-Matrix Multiplier /
compute-in-memory core, in Chisel. Computes `y = a^T * W`: a stationary
32x32 signed INT4 weight matrix `W`, a 32-element signed INT4 activation
vector `a`, a 32-element output vector `y` with full-scale-safe
accumulation.

Around the core sits the start of the full system in
`docs/riscv-cim-diagram.pdf`: a tightly coupled data memory, a job-based
accelerator wrapper, and (until the RISC-V host is in) a small scheduler
that runs a 784-64-10 MNIST MLP on it -- see "CIM system" and
"Integration" below.

## Width sizing ("full scale carry, while ensuring resolution")

- signed 4b x 4b product: needs 8 bits (`-8 * -8 = 64`, which overflows a
  7-bit signed range)
- summing 32 such products: needs `log2(32) = 5` more guard bits

So the per-column accumulator is `8 + 5 = 13` bits -- wide enough that
*every* column sum is exact even in the literal worst case (all 32 taps at
`-8 * -8` simultaneously), with no saturation, clipping, or rounding
anywhere in the datapath. `VmmParams` computes this from `dim`/`inWidth`
generically rather than hardcoding 13 (see `VmmParams.scala`).

## Architecture

```
src/main/scala/vmm/
  VmmParams.scala      dim/inWidth/accWidth/macLatency sizing (proof
                        above, as code)
  MacBank.scala        dim x dim fully-spatial MAC array: every weight
                        held in a register grid, all dim x dim products
                        computed in parallel each pass, reduced per-column
                        by a pipelined binary adder tree
  Vmm.scala            core: edge-detects aValid into MacBank's start
                        pulse, exposes the aValid/yValid handshake
  VmmController.scala  VmmSequencer: the sequencing FSM for Vmm -- turns the
                        32-cycle weight load + aValid-pulse + yValid-wait
                        protocol into a plain loadStart / weightRow
                        (Decoupled) / computeStart / busy / done / error
                        handshake, reaching the array through a port.
                        VmmController: VmmSequencer + Vmm as one module
  cim/                 the system around the array -- see "CIM system" below
  mlp/                 MlpConfig / MlpRefModel (network shape, bit-exact
                        integer reference model); MlpEngine (superseded
                        by cim/, see PLAN.md)
  Main.scala           Verilog-emission entry points: `Main` -> Vmm.v,
                        `MainController` -> VmmController.v, `MainCim` ->
                        CimSystem.v + tcdm_bank.v (see caveat below)
```

**Why 32x32 *and* INT4 together.** A fully-spatial `dim x dim` grid of
one-multiplier-per-cell processing elements needs `dim^2` multipliers --
4096 at the original dim=64/INT8 design, or still ~1024 at dim=32/INT8.
Either way, mapped to DSP48E1 slices that's several times the Pynq-Z2's
entire budget (xc7z020: 220 DSP slices) -- ~18.6x over at dim=64, ~4.7x
over at dim=32. Dropping to INT4 doesn't change the DSP math (a DSP slice
is a DSP slice regardless of operand width below its max), but it does
make each of the 1024 multipliers small enough (4b x 4b) to plausibly map
to LUT fabric instead of DSPs -- the combination of *smaller array*
(32x32, not 64x64) *and* *narrower operands* (INT4, not INT8) is what
makes a fully-spatial, no-DSP, no-time-multiplexing design plausible;
neither alone gets there. The previous design (`dim=64`, INT8,
time-multiplexed across DSPs) is what this replaces.

**LUT budget: synthesis checkpoint run, design fits.** The Zynq-7020's LUT
budget is quoted differently by different sources (the datasheet says
53,200; other recollections put it closer to 98k) -- rather than trust
either figure, `fpga/synth_check.tcl` runs a synthesis-only (no place &
route) build against `generated/Vmm.v` with DSP inference forced off
(`-max_dsp 0`) and reports `report_utilization`'s LUT/DSP rows, which is
the authoritative number for whatever Vivado install/part database is
actually in use.

Run against Vivado 2024.1 / `xc7z020clg400-1` (2026-08-27,
`fpga/synth_check_utilization.rpt`):

| Resource | Used | Available | Util% |
|---|---|---|---|
| Slice LUTs | 27,489 | 53,200 | 51.67% |
| Slice Registers | 23,432 | 106,400 | 22.02% |
| DSPs | 0 | 220 | 0.00% |

Confirms both open questions: the datasheet's 53,200 LUT figure is what
this Vivado/part database actually reports (not the ~98k recollection),
the fully-spatial 32x32 INT4 array fits with headroom (51.67%), and
`-max_dsp 0` does suppress DSP inference on this Vivado version (0 of 220
DSPs used -- all 1024 4x4 multiplies landed in LUT fabric as intended, no
fallback to per-cell `use_dsp = "no"` attributes needed). Note this is
pre-`opt_design`/pre-implementation, so the real post-P&R LUT count is
typically *lower*, not higher, than the number above.

**Dataflow.** The `dim x dim` weight matrix lives in `weightMem`, a dense
register grid (not a synchronous memory) -- every weight must be readable
combinationally every cycle a pass is active, since nothing is
time-multiplexed anymore. On `start`, all `dim x dim` products are
computed in one cycle and registered; each column's `dim`-term sum is
then reduced by a **pipelined** binary adder tree, one register stage per
level (`log2Ceil(dim)` levels for a power-of-two `dim`), rather than one
wide combinational reduction. Total latency from `start` to `valid` is a
fixed `p.macLatency = log2Ceil(dim) + 1` cycles (6 cycles at dim=32) --
*constant* in `dim`, unlike the prior design's `dim`-cycle streaming
latency. `Vmm` edge-detects `aValid` (so a level held high by, e.g., a
VIO output doesn't repeatedly restart the pass) into a single-cycle
`start` pulse for `MacBank`, delayed one more cycle so the registered
activation vector has settled before the multiply stage reads it -- so
`yValid` pulses `macLatency + 1` cycles (7 at dim=32) after `aValid`.
`Vmm` forwards `MacBank`'s `valid`/`y` back out as `yValid`/`y`.

Widths grow with the data: products are registered at `prodWidth` (8
bits) and each adder-tree level adds one bit (9, 10, 11, 12, 13 at
dim=32), reaching `accWidth` at the last level. The product and
intermediate tree registers are free-running (no clock enable, no reset)
-- only the `valid` chain and the final, `y`-driving level are gated --
because one flip-flop enabling thousands of registers was a zero-logic,
all-routing critical path.

**One pass at a time -- now a deliberate choice, not a resource
necessity.** `aValid` must wait for the prior `yValid`, same as the old
design. Unlike the old design, though, this datapath has *no* actual
shared/reused resource forcing that restriction -- the pipelined array
could in principle accept a new `aValid` every cycle for true
back-to-back throughput. The restriction is kept anyway because it
matches what a future controller expects and avoids adding arbitration
logic (a `weightLoad` racing an in-flight pass) this design doesn't need
to solve yet. Revisit once controller work (see "Integration") wants
back-to-back throughput.

**Weight load.** One matrix row per cycle (`weightLoad` + `weightRow` +
`weightRowData`, `dim` cycles for the full matrix) -- deliberately not
tied to any particular bus width, since that serialization is a wrapper
concern (AXI-Lite beats vs. TileLink burst vs. RoCC multi-instruction) to
be decided when this gets attached to A-Core. A `weightLoad` racing an
in-flight pass is safe: `MacBank` captures that pass's products from
`weightMem` combinationally on the same cycle `start` is asserted, before
that cycle's write (if any) takes effect, so an in-flight pass always
finishes against the weights that were resident when it started and a
concurrent `weightLoad` only affects the next pass (see `VmmSpec` for a
regression test of this).

## CIM system (`src/main/scala/vmm/cim/`)

The structure of `docs/riscv-cim-diagram.pdf`, minus the RISC-V host and
the peripherals, at 32x32 instead of 64x64:

```
  host port ──────┐
 (harness now,    ├─► Tcdm ◄──► CimAccel ◄──► Vmm
  RISC-V later)   │   4 x 8 kB     │           the array, a separate module
  MlpScheduler ───┘   block RAM    │           (stand-in for the macro)
   (temporary) ── register port ───┘
```

- **`Tcdm`** -- tightly coupled data memory: four 8 kB block-RAM banks
  behind a single-cycle interconnect (masters on different banks are served
  together, round-robin on the same bank), OBI-shaped ports. It holds the
  image, the weights and biases, and the hidden activations and logits a
  run produces; `CimMap` is the memory map.
- **`CimAccel`** -- the digital wrapper around the array: control/status
  registers, a `Streamer` (TCDM to array and back, one 32-bit word per
  cycle), the array's `VmmSequencer`, and `PostProc` (accumulate across
  passes, bias, ReLU, requantise). It runs *jobs* and knows nothing about
  the network: set the weight / input / bias / result pointers and a mode
  word, write START, wait for `done`.
- **`MlpScheduler`** -- temporary. It plays the firmware: issues the 52
  jobs of the 784-64-10 MLP over the register port, then reads the logits
  from the TCDM for the argmax. When the RISC-V core arrives it takes over
  the register port and the host port and this module is deleted; nothing
  else changes.
- **`CimSystem`** -- the four wired together.

One inference is 8184 cycles (~82 us at 100 MHz); loading each 32x32 tile
from the TCDM a word at a time (128 cycles) is most of it.

## Building and testing

Matches Chipyard's toolchain versions (Chisel 3.6.0 / Scala 2.13.10 / sbt
1.8.2 / chiseltest 0.6.0) so this drops into a Chipyard-style build later
without a version fight.

```
sbt compile
sbt test
```

`VmmSpec` checks small-dim random matrices/vectors against a golden Scala
model (including a non-default width, to prove genuine parametricity),
the literal full-scale worst case, that output holds steady across idle
cycles (no re-accumulation), that `yValid` fires at exactly
`macLatency + 1` cycles, the weight-load-races-in-flight-pass regression,
and the real dim=32/inWidth=4/accWidth=13 target size end-to-end.

`VmmControllerSpec` drives only `VmmController`'s high-level handshake
(`loadStart` / `weightRow` / `computeStart` / `busy` / `done` / `error` /
`y`) and checks the result against the same golden model: a single
load+compute at small dim, several passes against one loaded matrix,
matrix reload, a stalling weight source (Decoupled `valid` deasserted
mid-load), a start strobe held high (VIO-style level drive) triggering
exactly one operation, the load-stall timeout and compute timeout both
latching the sticky `error`, a mid-load `loadStart` restart, and the full
dim=32 target size — i.e. that the FSM sequences the core's protocol
correctly and fails safe when it can't.

`sim/tb_vmm.v` and `sim/tb_vmm_controller.v` are plain Verilog-2001
self-checking testbenches (for Vivado xsim) for the core and the
controller respectively — same golden model, `PASS:`/`FAIL:` at
`$finish`.

`vmm.cim`'s specs cover the new system: `TcdmSpec` (storage, byte enables,
parallel and contended access), `CimAccelSpec` (single jobs against plain
integer arithmetic) and `CimSystemSpec` (every committed golden image,
loaded through the host port, bit-exact against `MlpRefModel` on the
hidden activations, the logits and the class).

At dim=32 (1024 PEs), treadle (chiseltest's default Scala-interpreter
backend) is fine for the `Vmm` / `VmmController` tests, including the
full-size ones. The `vmm.cim` and `MlpEngine` specs need **Verilator**
(the full network is far too slow on treadle, and the TCDM banks are a
Verilog black box), and in this environment Verilator needs a `g++` shim
and ccache disabled to get past a GCC precompiled-header bug -- see
`CimSystemSpec`'s doc comment:

```
PATH=$PWD/.toolshim:$PATH CCACHE_DISABLE=1 sbt test
```

### Caveat: Verilog emission uses the classic Scala FIRRTL Compiler

`sbt "runMain vmm.Main --target-dir generated"` emits `generated/Vmm.v`;
`sbt "runMain vmm.MainController --target-dir generated"` emits
`generated/VmmController.v` (which contains `Vmm` and `MacBank` too — the
FPGA bring-up flow in `fpga/` uses this file, and must not also add
`Vmm.v` or those modules collide). The modern `circt.stage.ChiselStage`
path needs an external `firtool`
binary version-matched to Chisel 3.6.0 (~1.37.0); the only `firtool`
builds present on this machine are much newer CIRCT nightlies that
renamed a flag Chisel 3.6.0 still passes (`-dedup` -> `--no-dedup`), so
that path fails with `Unknown command line argument '-dedup'`. `Main.scala`
falls back to the classic, dependency-free Scala FIRRTL Compiler
(`chisel3.stage.ChiselStage`) instead -- deprecated upstream but needs no
external binary. Switch it back to `circt.stage.ChiselStage` once a
matching `firtool` is available (Chipyard pins one).

### Timing closure: verified fixed at 100MHz

The prior dim=64 design failed timing closure at 100MHz (WNS -14.9ns, see
`fpga/README.md`'s history) with an *unpipelined* per-cycle
multiply+accumulate in the critical path -- a likely contributor. This
design's adder tree is deliberately pipelined (one register per level,
not one wide combinational reduction) specifically to avoid repeating
that shape.

`fpga/timing_check.tcl` runs a full out-of-context synth/opt/place/route
(same `-max_dsp 0` as the resource checkpoint) with a 100MHz (10ns) clock
constraint on `clock`, then `report_timing_summary`. Top/source default to
`Vmm`/`generated/Vmm.v`; pass `-tclargs VmmController
generated/VmmController.v` for the FSM wrapper. Run against Vivado 2024.1 /
`xc7z020clg400-1`, post-route signoff numbers (not router-estimated):

| Metric | `Vmm` core (2026-08-27, not re-run) | `VmmController` (2026-10-10) |
|---|---|---|
| WNS (setup) | +0.327 ns | +0.220 ns |
| WHS (hold) | +0.134 ns | +0.098 ns |
| Failing endpoints | 0 / 38,375 | 0 / 25,033 |
| Slice LUTs | 27,489 (51.7%) | 25,396 (47.7%) |
| Slice Registers | 23,432 (22.0%) | 22,883 (21.5%) |
| DSPs | 0 / 220 | 0 / 220 |

Reports: `fpga/timing_check_report.rpt` /
`fpga/timing_check_VmmController_report.rpt` (and the `synth_check_*`
utilization pair). Timing is met at 100MHz on both setup and hold with
zero failing endpoints for both the core and the FSM wrapper -- the
pipelined adder tree fixes what the old unpipelined dim=64 design failed
at (WNS -14.9ns). The `Vmm` column and the LUT-budget table
above predate the 2026-10-10 `MacBank` cleanup (free-running, width-trimmed
datapath registers) and have not been re-run; the `VmmController` column
is post-cleanup. Setup slack at this constraint is not a measure of
margin: Vivado stops optimising once 10 ns is met, so the reported worst
path is a lazily routed zero-logic net and WNS lands at +0.1-0.4 ns run to
run (see `PLAN.md` for an 8 ns `MlpEngine` run that shows the real
margin). Re-run `fpga/timing_check.tcl` after any change to the controller
or datapath. Both runs are
OOC with `HD.CLK_SRC` unset, so clock-tree delay/skew is not modelled; the
number after full-device implementation with the real `FCLK_CLK0` BUFG
will differ.

## Integration

The attachment points for the RISC-V host are in place and are what the
temporary pieces use today (see "CIM system" above):

- the **TCDM host port** (`CimSystem.io.host`, OBI-shaped) -- the core's
  data port. The FPGA harness drives it now to write test images and read
  results back.
- the **accelerator register port** (`CimRegPort`: pointers, mode,
  START / status, plus a `done` event) -- to go behind the peripheral bus.
  `MlpScheduler` drives it now.

Remaining, per `docs/riscv-cim-diagram.pdf`: the core itself with its
instruction memory, an APB front end on the register port, the event unit
(`done` as an interrupt), and the peripherals. `VmmController` remains the
self-contained array + sequencer for the standalone JTAG harness
(`fpga/vmm_debug.v`).
