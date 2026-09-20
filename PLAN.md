# Plan — MLP inference on the VMM core

Redrafted 2026-09-06 from `C:\DOCUMTENS\riscv-vmm-2.docx` (design notes +
status snapshot + whiteboard photos) and `riscv-vmm.docx` (original 64×64
plan), reconciled against the current repo state. Supersedes the "downsize
to 32×32 INT4" plan, which is complete.

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
- Real-hardware end-to-end validation of the JTAG flow.

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

### Phase 1 — Offline model + quantisation

Produces the weight/bias artefacts the RTL consumes. No Chisel here.

- [ ] Train a 784-64-10 MLP (framework TBD — PyTorch likely), MNIST.
- [ ] QAT to INT4 weights **and** INT4 activations.
- [x] **FP → fixed-point ("fxd. F").** Per-tensor, power-of-two scales,
      MSE-calibrated (`calibrate.py`), frozen in `config.FXP` — see open
      decision #3. Requant between layers is an arithmetic shift + clamp
      to `[-8, 7]`, no multiplier.
- [ ] Bias: quantise to the accumulator's fixed-point (INT13, or the wider
      partial-sum type) so it adds directly, not to INT4.
- [ ] Export: `layer1_w.hex`, `layer1_b.hex`, `layer2_w.hex`,
      `layer2_b.hex` in tile-major order matching the loader, + the scale
      shifts. Commit a handful of MNIST test images + expected logits as
      golden vectors.
- [ ] `netron` sanity-check of the exported graph.
- [x] Record measured accuracy (float vs INT4) — **2026-09-13: float
      97.70%, INT4 97.23%, delta +0.47pp.** Well inside an acceptable
      floor for MNIST; see open decision #5.

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
PATH=./.toolshim:$PATH CCACHE_DISABLE=1 sbt "testOnly vmm.mlp.MlpEngineSpec"
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
- [ ] Latency report (cycles/inference) — one data point in hand: ~2188
      cycles/inference (measured in `MlpEngineSpec`'s passing run), against
      a rough hand-estimate of ~2192. Still open: a proper writeup, and
      confirming it's identical across all 16 images (tile counts are
      image-independent, so it should be — not yet explicitly checked).
- [ ] Small-dim parametric chiseltest variant, if feasible — `MlpEngine`
      is currently hardwired to `MlpConfig`'s fixed 784-64-10 shape, not
      parametric like `VmmParams`.

### Phase 5 — FPGA bring-up of the MLP

- [ ] `mlp_debug.v` (or extend `vmm_debug.v`): VIO to load an image +
      trigger, ILA to capture `class` / logits. Weight BRAM initialised
      from the Phase 1 hex at bitstream build.
- [ ] Regenerate Verilog (`MainMlp` emission entry), re-run
      `synth_check` / `timing_check` for the new top — **expect LUT growth**
      from the accumulators + BRAM; check it still fits xc7z020 and holds
      100 MHz.
- [ ] Run on the board via XSCT/Hardware-Manager (see
      `fpga/MANUAL_SETUP.md`), confirm predicted classes on the golden
      images. **This is the first real end-to-end hardware run for the
      whole stack** — the core/controller JTAG flow is still unvalidated
      on silicon.

### Phase 6 — Bus attachment (parallel track, unblocks A-Core)

Wraps `VmmController` (or `MlpEngine`), does not change it.

- [ ] **Decide the bus** — AXI-Lite vs RoCC vs TileLink. Needs A-Core's
      actual accelerator-attachment interface (from its RTL/docs, not
      guessed). AXI-Lite is the portable default; RoCC if A-Core exposes a
      custom-instruction port.
- [ ] AXI-Lite register map sketch: weight-row write port + push, `a`/image
      write port, control (`loadStart`/`computeStart`/`imgLoad`), status
      (`busy`/`done`/`error`), `y`/`class` readback. Bus width drives
      write serialisation into the wide internal ports.
- [ ] chiseltest with a small AXI-Lite driver, same golden-model check.

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
4. **Image source on hardware** — JTAG-loaded BRAM vs compiled-in fixed
   test image vs AXI DMA.
5. ~~**Accuracy floor**~~ — **signed off 2026-09-13.** Evaluated the
   committed checkpoints against the frozen `FXP` above (`test.py`/
   `train.py:evaluate` on the MNIST test set): float **97.70%**, INT4
   (QAT, weights+activations) **97.23%**, delta **+0.47pp**. Accepted as
   the floor — Phase 3 RTL can proceed against `artifacts/golden.json`.

---

## Immediate next step

Phases 0, 1, 2 and 3 are all **done**: both blocking Phase 1 decisions
(#3 fixed-point, #5 accuracy) are signed off, and `MlpEngine` (Phase 3
RTL) passes end-to-end on all 16 golden images (2026-09-13) — see the
Phase 3 section's "Status"/"Verification status" notes, including two
real bugs found and fixed only by actually simulating it.

Next is Phase 4: a latency report (cycle count per inference — already
have one data point, ~2188 cycles, from the passing run) and a
small-dim parametric chiseltest variant if feasible. Worth fixing the
Verilator/GCC PCH bug upstream (or pinning a working Verilator version)
first, so Phase 4 doesn't depend on the local `.toolshim` + `CCACHE_DISABLE=1`
workaround. Phase 6 (bus attachment) can start in parallel once the bus
is chosen.

(Repo is being kept local for now — the `model/` + `src/` trees stay
untracked by choice, not blocked on anything.)
