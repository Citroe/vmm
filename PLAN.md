# Plan — MLP inference on the VMM core

Redrafted 2026-09-06 from `C:\DOCUMTENS\riscv-vmm-2.docx` (design notes +
status snapshot + whiteboard photos) and `riscv-vmm.docx` (original 64×64
plan), reconciled against the current repo state. Supersedes the "downsize
to 32×32 INT4" plan, which is complete. Restructured 2026-10-12 toward
`docs/riscv-cim-diagram.pdf` — see Phase 5b.

The docx's 7 embedded images are zooms of one hand-drawn Miro board. What
they add, on top of the text notes, is folded into the sections below:
- **Quantisation flow:** `32b FP → QAT → Quant → 4b INT (w, b)` *and* 4b INT
  activations; separately, `FP → fxd. F` — the float representation is
  converted to a **fixed-point** format, which is a distinct step from
  quantising the weights.
- **Partial-sum notation:** layer 1 is written as
  `O/p₁ = Σ¹⁻³²(imₐᵦ·W) [opar1] + Σ³³⁻⁶⁴(…) [opar2] + … + Σ⁷⁵¹⁻⁷⁸⁴(…) [opar25]`,
  with a `Rst/Read L-1` accumulator (`θx`) looping to sum the 25 partials.
- **Post-accumulation ops:** a braced group `{ bias, ReLU, P.S }` (P.S =
  partial-sum accumulate) applied at the accumulator/output stage.
- **On-FPGA block diagram:** an `FPGA` box holding `RAM/vmm` (tagged `CIM`)
  + `CS` (control sequencer) + `FSM`, `O/p` out one side, external stimulus
  in the other, fed by a `WMC` (weight-memory controller). Offline side:
  `trained 2-layer MLP → coefficients`.
- One token near the MLP/QAT flow (`2CU`?) is not legible — possibly "2
  conv units/layers", noted under *Later* as an open reading.

---

## Where things stand

**Done and verified**

- **Core** (`src/main/scala/vmm/`): 32×32 fully-spatial INT4 weight-stationary
  VMM, `y = aᵀ·W`. `dim=32, inWidth=4 → accWidth=13`. Fixed
  `macLatency = log2Ceil(dim)+1`. Pipelined per-column adder tree.
- **`VmmController`**: 4-state sequencing FSM wrapping `Vmm` — turns the
  32-cycle weight load + `aValid` pulse + `yValid` wait into a
  `loadStart` / `weightRow` (Decoupled) / `computeStart` / `busy` / `done`
  / `error` handshake. Edge-detects the start strobes for level-drivers.
- **Tests**: `VmmSpec` (7) + `VmmControllerSpec` (6), all green on treadle,
  incl. the dim=32 target size.
- **Verilog**: `generated/Vmm.v` (core) and `generated/VmmController.v`
  (FSM + core), classic SFC.
- **FPGA bring-up** (`fpga/`): `vmm_debug.v` drives `VmmController` over
  JTAG (VIO + ILA); `create_debug_ip.tcl` / `build_bd.tcl` /
  `run_hw_test.tcl` updated; `MANUAL_SETUP.md` added.
- **Vivado 2024.1 signoff** (`xc7z020clg400-1`, OOC, `-max_dsp 0`,
  post-route): `VmmController` meets timing at 100 MHz — WNS **+0.393 ns**,
  WHS +0.096 ns, 0 failing endpoints; 27.9k LUTs (52.5%), 0 DSPs
  (post Phase-0 hardening; the critical path is in the datapath adder tree,
  so WNS moves with P&R variance, ~0.1–0.4 ns).
- **Phase 0 (`VmmController` hardening) — done.** Load-stall timeout →
  sticky `error`; mid-load `loadStart` re-strobe restarts the load;
  `loadTimeout` / `computeTimeout` are constructor params. `VmmControllerSpec`
  now 9 tests (error paths + restart covered). `sim/tb_vmm_controller.v`
  added (Verilog TB, passes in Verilator + intended for xsim).

**Not done**

- Any MLP-level logic (this plan).
- Bus attachment (AXI-Lite / RoCC / TileLink).
- Real-hardware validation of the standalone `VmmController` JTAG harness
  (`vmm_debug.v`). The full-network `CimSystem` harness passed on the
  board 2026-10-15 — see Phase 5's board-run item.

---

## Target (from the docx)

Two-layer MLP, MNIST-style 784-pixel input:

- **Layer 1**: 784 → 64, ReLU
- **Layer 2**: 64 → 10 → argmax → class

Weights and biases INT4, obtained by quantisation-aware training (QAT) of a
float MLP, then exported. `im2col` convolution support is a later
generalisation — MLP (dense) first.

### Why every layer needs multiple passes ("layer mapping")

The VMM does exactly one thing per pass: 32 activations `a` × a stationary
32×32 `W` → 32 outputs `y`. Every dimension in this MLP is bigger than 32,
in two independent ways, so a layer is never one pass:

1. **Input (contraction) dimension > 32 → accumulate across passes.**
   Layer 1 has 784 inputs; layer 2 has 64. The VMM only contracts 32 terms
   at a time and does **not** accumulate across passes (each `computeStart`
   recomputes `y` from scratch — see `Vmm.scala`'s "one pass at a time, no
   re-accumulation"). So the input vector is split into
   `⌈K/32⌉` **input-tiles**, each run against its own 32×32 weight block,
   and their 32-wide `y` results are summed *outside* the VMM in a
   partial-sum buffer held by `MlpEngine`. Layer 1: `⌈784/32⌉ = 25`
   input-tiles (the `opar1…opar25` in the docx; last tile carries 16 real
   inputs + 16 zero pad). Layer 2: `⌈64/32⌉ = 2` input-tiles — the 64
   hidden activations from layer 1 are **stored, then fed to the VMM in
   two passes**.
2. **Output dimension > 32 → repeat the whole thing per output-tile.**
   Layer 1 has 64 outputs → `⌈64/32⌉ = 2` **output-tiles**; layer 2 has 10
   → 1 output-tile (22 columns zero-padded). Each output-tile is an
   independent sweep over all the input-tiles.

Nothing about the image being 784 pixels is special here — it's just the
largest instance of case 1. Case 1 and case 2 multiply:

| Layer | W [K×N] | input-tiles `⌈K/32⌉` | output-tiles `⌈N/32⌉` | 32×32 passes | pad |
|---|---|---|---|---|---|
| 1 | 784 × 64 | 25 (last: 16 real rows) | 2 | 25 × 2 = **50** | K 784→800 |
| 2 | 64 × 10 | 2 | 1 (10 real cols) | 2 × 1 = **2** | N 10→32 |

So W is stored as a grid of 32×32 tiles: **50** for layer 1, **2** for
layer 2, **52** total. Zero-padded lanes contribute nothing, so the pad is
free arithmetically (just wasted cycles on the last layer-1 input-tile).

### The partial-sum buffer and the feedback loop

`MlpEngine` owns a **partial-sum buffer**: one 32-lane accumulator per
output-tile, wide enough for the cross-tile sum
(`accWidth + ⌈log2(input_tiles)⌉` ≈ 13 + 5 = **18 bit** for layer 1).

Per output-tile `o`:

```
psum[o] = 0
for k in 0 .. input_tiles-1:
    MlpEngine → VmmController: loadStart, then stream the 32 rows of W_tile[k][o]
    MlpEngine → VmmController: computeStart with activation-tile k (32 INT4 lanes)
    wait done
    MlpEngine reads VmmController.io.y  (32 × INT13)   ← the read-back
    psum[o] += y                                       ← accumulate outside the VMM
# after the last input-tile:
psum[o] = psum[o] + bias[o]
psum[o] = ReLU(psum[o])            # layer 1 only
h[o]    = requant(psum[o])         # INT18 → INT4, arithmetic shift + clamp [-8,7]
```

`h` (64 INT4 values for layer 1) goes into the activation buffer, which is
then **fed back** into the same loop as the input for layer 2. This
store-partials / read-`y`-back / re-drive-the-controller cycle is the core
of `MlpEngine`; the VMM and `VmmController` are unchanged, just called
`50 + 2 = 52` times per inference (plus the weight reload before each).

---

## Phases

### Phase 0 — `VmmController` hardening — **DONE (2026-09-06)**

De-risked everything built on top of the FSM.

- [x] **Load-side timeout + guard.** `sLoad` now counts *consecutive
      stalled* cycles (resets on every accepted row); after `loadTimeout`
      (default 1024) it aborts into the sticky `error` and returns to
      `sIdle`, so a miscounted burst / dead master can't wedge it with
      `busy` stuck high. Also: a `loadStart` rising edge *during* `sLoad`
      restarts the load from row 0 (explicit recovery for a driver that
      lost count).
- [x] **`error` paths tested.** `VmmControllerSpec` +3 cases: load stalls
      and never delivers rows → `error` latches, sticky, clears on next
      start, recovers; compute never sees `yValid` (via a deliberately
      tiny `computeTimeout`) → `error` latches; mid-load `loadStart`
      restart → correct result against the second matrix. 9 tests total,
      all green.
- [x] **`sim/tb_vmm_controller.v`** — Verilog-2001 TB parallel to
      `tb_vmm.v`: pulse `loadStart`, stream 32 rows, pulse `computeStart`,
      check `y` vs golden + `error` low. Passes in Verilator; for xsim per
      `fpga/MANUAL_SETUP.md`.
- [x] **Timing re-checked** — `fpga/timing_check.tcl -tclargs
      VmmController …` after the change: WNS +0.393 ns, WHS +0.096 ns,
      0/43,785 failing, 27.9k LUTs. Reports in `fpga/`.
- New constructor params: `loadTimeout: Int = 1024`,
  `computeTimeout: Int = 0` (0 ⇒ `macLatency + 4`). Defaults keep the
  prior behaviour; `Main`/`MainController` unaffected.

### Phase 1 — Offline model + quantisation — **DONE (2026-09-13)**

Produces the weight/bias artefacts the RTL consumes. No Chisel here.
Code in `model/` (see `model/README.md`); tidied 2026-10-10 with the
committed artefacts re-exported byte-identical.

- [x] Train a 784-64-10 MLP on MNIST — PyTorch, `train.py`.
- [x] QAT to INT4 weights **and** INT4 activations — hand-rolled fake-quant
      + straight-through estimator (`quant.py`).
- [x] **FP → fixed-point ("fxd. F").** Per-tensor, power-of-two scales,
      MSE-calibrated (`calibrate.py`), frozen in `config.FXP` — see open
      decision #3. Requant between layers is an arithmetic shift + clamp
      to `[-8, 7]`, no multiplier.
- [x] Bias: quantise to the accumulator's fixed-point (INT13, or the wider
      partial-sum type) so it adds directly, not to INT4.
- [x] Export: `layer1_w.hex`, `layer1_b.hex`, `layer2_w.hex`,
      `layer2_b.hex` in tile-major order matching the loader, + the scale
      shifts. Commit a handful of MNIST test images + expected logits as
      golden vectors.
- [x] `netron` sanity-check of the exported graph (`to_onnx.py`, see Phase 2).
- [x] Record measured accuracy (float vs INT4) — **2026-09-13: float
      97.70%, INT4 97.23%, delta +0.47pp.** Well inside an acceptable
      floor for MNIST; see open decision #5. **2026-10-10:** `evaluate.py`
      also runs all 10k test images through the integer reference model
      (the thing the RTL is bit-exact against): **97.22%**, disagreeing
      with the fake-quant float path on 12/10000 images.

Known soft spots, none blocking: the test set doubles as the
model-selection and calibration set (so 97.2% is slightly optimistic — a
held-out validation split would fix it, at the cost of new artefacts), and
`config.FXP` / `refmodel.py` / `export.py` are written for exactly two
dense layers, mirroring the hardwired `MlpEngine`.

### Phase 2 — Scala reference model — **DONE (2026-09-08)**

- [x] Pure-Scala bit-exact model of the tiled, quantised MLP (tile
      accumulation, bias, ReLU, requant shift/clamp, argmax):
      `src/main/scala/vmm/mlp/{MlpConfig,MlpRefModel}.scala`. `MlpConfig`
      mirrors `model/config.py` (tiling, widths, `Fxp` exponents);
      `MlpRefModel` ports `refmodel.py` line-for-line, integer-only, and
      loads params straight from the committed `.hex` (so it also exercises
      the WMC loader contract).
- [x] Cross-checked against the Phase 1 Python reference on all 16 golden
      images — `hidden_int4`, `logits_int`, `pred` match **bit-for-bit**
      (`MlpRefModelSpec`, 4 tests; `sbt test` now 20 green). `build.sbt`
      gains `json4s-native % test` (pinned to firrtl's transitive 4.0.6).
- [x] Netron: `model/to_onnx.py` exports the float graph to
      `model/mlp_float.onnx` (gitignored); `netron model/mlp_float.onnx`
      shows `Gemm 784×64 → Relu → Gemm 64×10`, `onnx.checker` clean. Does
      **not** show the fixed-point convention — topology sanity only.

### Phase 3 — `MlpEngine` RTL

A sequencer around `VmmController` (which it instantiates or is handed).
Block structure, matching the docx's on-FPGA diagram:

```
            ┌───────────────────── MlpEngine ─────────────────────┐
  imgLoad ─►│  activation RAM ─┐                                  │
            │   (img 784×I4,   │    ┌──────────┐   loadStart      │
            │    hidden 64×I4) ├───►│    CS     ├── weightRow ────┼─► VmmController ─► Vmm
    start ─►│                  │    │ (tile     │  computeStart   │        │   (CIM)
            │  weight RAM ◄─────┼────┤  scheduler│◄──── done ──────┼────────┘
            │   (52×[32×32] I4) │    │   = FSM)  │◄──── y ─────────┼─ read-back
      WMC ─►│   filled once     │    └─────┬─────┘                 │
            │  partial-sum buf ◄──(+bias, ReLU, requant)           │
            │   (out_tiles×32 × I18)     │                         │
            │        argmax ────┼────────┴──────► class ───────────┼─► O/p
            └───────────────────┴─────────────────────────────────┘
```

**Status (2026-09-13): DONE and verified.** Written as
`src/main/scala/vmm/mlp/MlpEngine.scala`, elaborates cleanly
(`sbt "runMain vmm.MainMlp"`), and passes end-to-end on all 16 committed
golden images — see "Verification status" below. Deviates from the
diagram in one place: weight/bias fill is a runtime streaming port
(`weightLoad`+`weightRow`, `biasLoad`+`biasWord`, same Decoupled shape as
`VmmController.weightRow`), not `$readmemh` — keeps the whole engine
pokeable in chiseltest without depending on Verilog file-loading
semantics; swap to `loadMemoryFromFileInline` later if a from-bitstream
weight ROM is wanted.

- [x] **Weight RAM (`WMC`-filled)**: all 52 tiles (50 layer-1 + 2 layer-2)
      as 32×32 INT4, one flat scalar-element `Mem` per lane (`weightMems`)
      rather than one `Mem` of `Vec` — the aggregate-typed version doesn't
      lower to a real memory primitive on the classic FIRRTL compiler this
      project targets, ballooning into a per-element register array plus a
      huge read mux (see MlpEngine's doc comment). `WMC` is folded into the
      CS FSM's own load states rather than a separate module.
- [x] **Activation RAM**: banked per input-tile (25 banks of 32 INT4) so a
      compute-path read is one cheap `inputTiles`-way mux, not one huge
      dynamic index into a flat 800-entry register file (the naive version
      of this had the same aggregate-Mem-shaped blowup, see below).
      Image pixels fill the banks; the hidden layer overwrites banks 0-1
      once layer 1 fully completes (both its output-tiles must finish
      reading the image first).
- [x] **Partial-sum buffer**: one 18-bit accumulator per VMM lane, reused
      across both layers (layer 2 just uses the low bits).
- [x] **`CS` — tile-scheduler FSM**: `sTileStart → sFeedW → sCompStart →
      sCompWait → sPostTile`, exactly the loop in "The partial-sum buffer
      and the feedback loop" above, plus `sCommitHidden` between layers and
      `sArgmax` at the end.
- [x] **Post-tile (`{bias, ReLU, P.S}` in the diagram)**: bias add at psum
      width, ReLU + arithmetic-shift requant to INT4 (layer 1), or raw
      psum+bias as logits (layer 2).
- [x] **Layer 2** reuses the whole path; `sArgmax` computes `cls` over the
      10 real logits.
- [x] **Top-level handshake**: `imgLoad`/`start`/`busy`/`done`/`cls`, plus
      the weight/bias load pulses above. Edge-detected strobes, mirroring
      `VmmController`.

**Verification status: PASSING (2026-09-13).** `MlpEngineSpec` (chiseltest)
drives the real top-level handshake with the committed artefacts and
checks the RTL against `MlpRefModel` (already proven bit-exact vs.
`model/refmodel.py`) — weights + bias loaded once, then every one of the
16 committed golden images run end-to-end, all matching bit-for-bit on
both the 10 raw logits and the predicted class. Full run: ~18.5s.

Two real things surfaced only by actually simulating, both fixed:
- **`MlpEngine` bug**: `sArgmax` wrote `clsReg` (a register, visible only
  the *next* cycle) but pulsed `done` that same cycle, so a reader
  sampling on `done` saw the previous/reset class, not the one just
  computed. Fixed the same way `VmmController` already handles `io.y`:
  also drive `io.cls` combinationally in that state.
- **Test-harness bug**: the `runInference` helper checked `done` right
  after stepping, then returned without stepping again — but `state` only
  actually lands back in `sIdle` on that *next* step, so the following
  image's `imgLoad` pulse landed one cycle too early and was silently
  dropped (looked like a hang: `while(!ready) step` spinning forever).
  Fixed by adding the one settle cycle `VmmControllerSpec`'s own
  `compute()` helper already takes after reading `y`.

Getting here also needed the **Verilator backend**, not chiseltest's
treadle default: treadle interpretation of this design is not just slow
but pathologically so once real data is toggling (~20s/beat measured
mid-run, not the ~1.1s/cycle idle-stepping rate first measured — a full
run extrapolated to 1-2+ *days*, not the "1.5-2h" first estimated from
idle-cycle timing). `VerilatorBackendAnnotation` compiles the design
instead of interpreting it: the same work drops to ~350ms *per image*,
roughly 10,000x faster. Verilator itself hit a real, known upstream
Verilator/GCC precompiled-header bug in this environment (`fatal error:
VMlpEngine__pch.h.slow: No such file or directory` — verilator/verilator
#4730, #4839, #5139, #5244, #5698); worked around locally with a `g++`
shim (`.toolshim/`, gitignored, environment-specific, not committed) that
creates the placeholder GCC's auto-PCH lookup needs. `ccache` (which
Verilator's Makefile always wraps the compiler with here) will cache-hit
that step on a repeat run and skip the shim, silently reintroducing the
bug — so every run needs `CCACHE_DISABLE=1` too:
```
PATH=$PWD/.toolshim:$PATH CCACHE_DISABLE=1 sbt "testOnly vmm.mlp.MlpEngineSpec"
```
See `MlpEngineSpec`'s doc comment for the full detail. Fixing this
upstream (or pinning a Verilator version without the bug) would remove
the need for the shim/env-var dance; worth doing before Phase 4's fuller
verification work (latency report, parametric small-dim variant) leans on
this routinely.

Ordering / reuse: start simple — reload weights per (output-tile,
input-tile), i.e. 52 full 32-row loads per inference. Layer 1 reloads each
of its 50 tiles exactly once (each `(k, o)` pair is distinct), so there is
actually no reload waste there; only a future *batched* inference (many
images, same weights) would benefit from keeping a tile resident across
images. Revisit as a throughput item, not correctness.

### Phase 4 — `MlpEngine` verification

- [x] chiseltest: drive `imgLoad` + `start` with the golden images,
      compare `class` and raw logits to the Phase 2 model — `MlpEngineSpec`,
      done as part of Phase 3's own verification (2026-09-13). Small-dim
      parametric variant still open (see below).
- [x] Full-size (784-64-10) end-to-end on all golden images — all 16 pass
      bit-exact (2026-09-13).
- [x] Latency (cycles/inference) — **2199 cycles** from `start` to
      `done`, identical for all 16 images (measured by the bring-up
      harness's `cycles` counter in `sim/tb_mlp_debug.v`, 2026-10-10);
      ~22 µs at 100 MHz. The earlier ~2188 figure predates the
      one-compare-per-cycle argmax. The harness reports the same counter
      on the board.
- [ ] Small-dim parametric chiseltest variant, if feasible — `MlpEngine`
      is currently hardwired to `MlpConfig`'s fixed 784-64-10 shape, not
      parametric like `VmmParams`.

### Phase 5 — FPGA bring-up of the MLP — **DONE (2026-10-15)**

- [x] **Harness written (2026-10-10)** — `fpga/mlp_debug.v` (VIO + ILA)
      around `fpga/mlp_debug_core.v`: weights, biases and the 16 golden
      images are ROMs built from `fpga/mem/*.mem` (`fpga/gen_mlp_mem.py`),
      loaded into the unchanged `MlpEngine` by a small FSM after reset;
      each result is checked on-chip against the golden class + logits
      (`pass_mask`). `sim/tb_mlp_debug.v` passes in Verilator, 16/16.
      Build: `fpga/build_mlp.tcl`; board procs: `fpga/run_mlp_hw_test.tcl`
      (passed on the board 2026-10-15). See `fpga/README.md`.
      Bitstream builds (full device, PS7 `FCLK_CLK0` 100 MHz, `-max_dsp 0`):
      WNS +0.164 ns, WHS +0.051 ns, 0 failing; 33.4k LUTs (62.8%), 35
      block-RAM tiles (25%), 0 DSPs.
- [x] Regenerate Verilog (`MainMlp` emission entry), re-run
      `synth_check` / `timing_check` for the new top — **done 2026-10-10**
      (Vivado 2024.1, OOC, `-max_dsp 0`, reports in `fpga/*MlpEngine*`).
      First cut (LUTRAM weights, combinational 10-way argmax): 40.6k LUTs
      post-synth (76.4%), **WNS −14.97 ns**, 23,008 failing endpoints.
      After moving the weight store to a `SyncReadMem` of packed 128-bit
      rows read through a sequential pointer, and making argmax one
      compare per cycle: 34.6k LUTs post-synth (65.0%), 32.7k post-route
      (61.5%), 7.5 block-RAM tiles, **WNS +0.134 ns**, 0 failing.
      Superseded later the same day by the `MacBank` cleanup below
      (30.9k LUTs post-route, WNS +0.168 ns).
      (The absolute `$PWD/.toolshim` path matters: Verilator's make runs in
      another directory, so a relative shim path is silently skipped.)
- [x] Run on the board via XSCT/Hardware-Manager — **passed 2026-10-15**
      on a Pynq-Z2 (2026-10-12 `CimSystem` bitstream, Vivado 2024.1;
      steps in `fpga/README.md`). `mlp_run_all` → `pass_mask = FFFF`, all
      16 golden images match on class and all 10 logits; `mlp_run`
      reports 8185 cycles per inference (~82 µs at 100 MHz), the same as
      simulation. **First real end-to-end hardware run of the whole
      stack.** The harness runs `CimSystem` (Phase 5b), not `MlpEngine`.
      The standalone `VmmController` harness (`vmm_debug.v`) has still not
      been run on silicon.

### Phase 5b — TCDM + job-based accelerator — **DONE in simulation (2026-10-12), on the board (2026-10-15)**

Pre-bring-up review feedback: separate the VMM from the FSM, use more
block RAM, put the image and the model parameters in a tightly coupled
data memory (TCDM) and have the FSM stream from it into the VMM; later the
FSM goes and a RISC-V core drives the VMM and the TCDM directly. Target:
`docs/riscv-cim-diagram.pdf` (at 32x32 instead of 64x64).

`MlpEngine` did all of it in one module, so nothing survived removing the
FSM. The replacement, `src/main/scala/vmm/cim/`:

```
CimSystem
├─ Tcdm            4 banks x 2048x32 block RAM, 3-master single-cycle
│                  interconnect: host port · accelerator · scheduler
├─ CimAccel        control/status regs + Streamer + PostProc + VmmSequencer;
│                  runs jobs, knows nothing about the network
├─ Vmm             the array, now a sibling of the logic that drives it
└─ MlpScheduler    TEMPORARY: issues the 52 jobs + argmax, i.e. the firmware
```

- [x] **Array split from its FSM.** `VmmSequencer` is the old
      `VmmController` FSM with the array behind a port; `VmmController` is
      kept as `VmmSequencer` + `Vmm` with unchanged IO for the standalone
      harness and its tests.
- [x] **TCDM** (`Tcdm.scala`, `src/main/resources/tcdm_bank.v`). 32 kB as
      in the diagram; needs 27.4 kB: image 400 B, hidden 32 B, logits
      128 B, biases 384 B, weights 26,624 B. Memory map in `CimMap`.
      Bank = top address bits; round-robin per bank. The bank is a Verilog
      black box (byte enables + `$readmemh`).
- [x] **Job-based accelerator** (`CimAccel.scala`). Registers: `WPtr`,
      `XPtr`, `BPtr`, `YPtr`, `Mode`, `Shift`, START / status. A job is any
      of: load a weight tile, clear or bias-preload the accumulators, one
      pass with accumulate, store INT4 activations (ReLU + round-shift +
      clamp) or raw sums. The streamer moves one 32-bit word per cycle.
- [x] **Post-processing** (`PostProc.scala`). The bias is the accumulators'
      starting value instead of a final add (same integer sum, 20-bit
      accumulators), and the accumulators are a ring read out one lane at a
      time, so ReLU / shift / clamp exist once instead of 32 times and the
      shift is programmable. This removes the old `psumReg -> hiddenReg`
      critical path.
- [x] **`MlpScheduler`** writes the registers exactly as firmware will and
      is the only MLP-specific RTL left. Replacing it with the core means
      handing over the register port and a TCDM master port.
- [x] **Verification.** `TcdmSpec` (3), `CimAccelSpec` (1), `CimSystemSpec`
      (all 16 golden images loaded through the host port; hidden
      activations, logits and class bit-exact vs `MlpRefModel`). `sbt test`
      26 green. `sim/tb_mlp_debug.v` on the rewritten harness, TCDM
      initialised from `fpga/mem/tcdm_bank{0..3}.mem`: 16/16.
- [x] **Harness** (`fpga/mlp_debug_core.v`): the weight/bias ROMs and the
      boot loader are gone (the parameters are the TCDM's initial
      contents); it keeps the 16-image ROM, copies an image in through the
      host port, starts a run, reads the logits back through the same port
      and checks them. Ports unchanged, so `mlp_debug.v`'s VIO/ILA are too.

Measured (Vivado 2024.1, `xc7z020clg400-1`, `-max_dsp 0`, 100 MHz,
out-of-context post-route, `fpga/timing_check_CimSystem_*`):

| | `MlpEngine` | `CimSystem` |
|---|---|---|
| LUTs | 30,857 (58.0%) | 27,032 (50.8%) |
| Registers | 28,023 | 24,140 |
| Block RAM tiles | 7.5 | 8 (the TCDM) |
| WNS @ 10 ns | +0.168 ns | +0.640 ns, 0 failing |
| Cycles / inference | 2199 | 8185 (~82 us) |

The latency cost is the word-serial tile load (128 cycles per tile instead
of 32); a bank-interleaved 128-bit fetch would recover it and is not
planned. `CimSystem`'s area includes the TCDM and its interconnect, which
`MlpEngine`'s figure had no equivalent of (its weights were a private RAM,
its activations and biases registers).

Full bitstream (`fpga/build_mlp.tcl`, whole device with PS7 clock, VIO and
ILA, `fpga/mlp_debug_{timing,utilization}.rpt`):

| | `MlpEngine` harness (2026-10-10) | `CimSystem` harness |
|---|---|---|
| LUTs | 33,424 (62.8%) | 30,974 (58.2%) |
| Registers | 31,240 | 28,531 |
| Block RAM tiles | 35 | 27 |
| WNS / WHS | +0.164 / +0.051 ns | +0.232 / +0.010 ns, 0 failing |

The first full build of this structure **failed** timing (WNS -0.459 ns, 4
endpoints) although the out-of-context run had passed at +0.293 ns: the
activation path (accumulator -> ReLU -> rounding add -> programmable shift
-> clamp, 22 logic levels) was one cycle. It now has a register after the
rounding add, at a cost of one cycle per activation store. Worth
remembering that the OOC check alone is not a signoff for the bitstream.

`MlpEngine.scala`, `MlpEngineSpec` and `MainMlp` are superseded but still
in the tree (they carry uncommitted changes); delete them once this is
committed.

### Phase 6 — RISC-V host (replaces "bus attachment")

The two attachment points exist and are exercised (Phase 5b): the TCDM
host port and the accelerator register port. Remaining, per
`docs/riscv-cim-diagram.pdf`:

- [ ] Core (RV32IMC, CV32E40P-class) + instruction memory; its OBI data
      port on the TCDM host port.
- [ ] APB front end on `CimRegPort`; `done` into an event unit / IRQ.
- [ ] Firmware that does what `MlpScheduler` does; then delete
      `MlpScheduler`.
- [ ] Peripherals (UART first, for results off the board without JTAG).

### Later / out of scope for now

- **`im2col` convolution front-end.** The docx puts `im2col` next to a
  small MAC-pipeline sketch and `≡ CL` (conv-layer) marks — the intent is
  that a conv layer is lowered to a dense matmul (`im2col` unfolds each
  sliding window into a column) and then runs through the *same* tiled
  `MlpEngine` path. So CNN support is mostly a front-end that reshapes the
  activation RAM + a different weight-tile layout, not a new datapath.
  One nearby token (`2CU`?) is unreadable — possibly "2 conv units" or "2
  conv layers" as the first CNN target; confirm against the board.
- Back-to-back activation throughput / weight double-buffering / keeping a
  weight tile resident across a batch of images.
- Switch Verilog emission back to `circt.stage.ChiselStage` once a
  firtool matched to Chisel 3.6.0 is available.

---

## Open decisions (need input)

1. **Bus**: AXI-Lite or RoCC? — depends on A-Core's interface.
2. **Training stack + QAT method** for INT4 weights *and* activations. —
   resolved in practice: PyTorch, hand-rolled fake-quant + STE (`quant.py`),
   trained via `train.py`.
3. ~~**Fixed-point convention + requant**~~ — **signed off 2026-09-13.**
   Per-tensor power-of-two scales, MSE-optimal (not max-abs) via
   `calibrate.py`, frozen in `config.FXP` / `artifacts/scales.json`:
   `e_act_in=-1, e_w1=-5, e_act_hidden=1, e_w2=-3` → `requant_shift=7`
   (layer-1 psum → hidden INT4, pure arithmetic shift, no multiplier).
   Bias adds at psum width (INT13/INT14), not INT4. Layer 2 has no requant
   (argmax is scale-invariant). `.hex`/`scales.json`/`golden.json` already
   committed under this convention (`manifest.json` hashes match).
4. ~~**Image source on hardware**~~ — **decided 2026-10-10:** the 16
   golden images compiled into a ROM, selected over VIO
   (`fpga/mlp_debug_core.v`). Weights/biases likewise from ROM, played
   into `MlpEngine`'s runtime streaming ports.
5. ~~**Accuracy floor**~~ — **signed off 2026-09-13.** Evaluated the
   committed checkpoints against the frozen `FXP` above (`train.py:evaluate`,
   now `evaluate.py`, on the MNIST test set): float **97.70%**, INT4
   (QAT, weights+activations) **97.23%**, delta **+0.47pp**. Accepted as
   the floor — Phase 3 RTL can proceed against `artifacts/golden.json`.

---

## `MacBank` fanout + width cleanup — **DONE (2026-10-10)**

Both changes are in `MacBank.scala`, bit-exact (`sbt test`, 21 green, incl.
`MlpEngineSpec` on all 16 golden images), Verilog regenerated, Vivado
re-run (2024.1, OOC, `-max_dsp 0`, post-route, reports in `fpga/`).

- [x] **Clock-enable fanout fix.** `prod` and the intermediate adder-tree
      registers are now free-running (no enable, no reset); only the last
      level — the one that drives `y` — is still enabled by the `valid`
      chain, so "`y` holds until the next `aValid`" and the
      racing-`weightLoad` behaviour are unchanged (both VmmSpec tests
      pass untouched).
- [x] **Datapath width trimming.** `prod` is `prodWidth` (8 b) and the
      tree grows 9, 10, 11, 12, 13.

| | before | after |
|---|---|---|
| `MlpEngine` LUTs, post-synth | 34.6k (65.0%) | 32.7k (61.5%) |
| `MlpEngine` LUTs, post-route | 32.7k (61.5%) | 30.9k (58.0%) |
| `MlpEngine` WNS @ 10 ns | +0.134 ns | +0.168 ns |
| `VmmController` LUTs, post-route | 27.9k (52.5%) | 25.4k (47.7%) |
| `VmmController` registers | 24.0k | 22.9k |
| `VmmController` WNS @ 10 ns | +0.393 ns | +0.220 ns |

Saving is ~1.8–2.5k LUTs and ~1.1k registers, less than the ~3k / ~5k
estimate: Vivado was already merging most of the redundant sign-extension
registers.

**The 10 ns WNS did not move, and is not a measure of margin.** Vivado
stops working once the constraint is met, so every run lands at
+0.1–0.4 ns with some lazily routed zero-logic net as the "worst" path
(now weight block RAM → `MacBank` `weightMem`, 7.2 ns of routing, in
`MlpEngine`; `valid` → `yReg` in `VmmController`). One extra `MlpEngine`
run constrained to **8 ns** (not saved in `fpga/`) gives the real picture:
WNS −0.272 ns, 60 failing endpoints, i.e. ≈8.3 ns / ~120 MHz achievable,
≈1.7 ns of true margin at 100 MHz. The worst path there is real logic in
`MlpEngine`'s post-tile stage (`psumReg` → `hiddenReg`: bias add + ReLU +
requant, 13 logic levels), not `MacBank`. No 8 ns run of the old RTL
exists, so how much of that margin this change bought is not measured.

Not scheduled, for later discussion: bit-serial activations (largest LUT
lever, ~+7% latency), activation banks into RAM, preloading the
accumulator with the bias (which would also shorten the post-tile path
above).

## Immediate next step

Phases 0-5b are done in simulation and through Vivado. Next is the board
run (Phase 5's open item): program the `mlp_debug` bitstream, `mlp_run_all`,
expect `pass_mask = FFFF` and 8185 cycles. After that, Phase 6.
