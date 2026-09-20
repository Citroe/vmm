package vmm.mlp

import chisel3._
import chisel3.util._

import vmm.{VmmController, VmmParams}
import MlpConfig._

/** Phase 3 skeleton: the tile-scheduler FSM + partial-sum buffer +
  * weight/bias/activation storage wrapping [[vmm.VmmController]], per
  * PLAN.md's on-FPGA block diagram and "The partial-sum buffer and the
  * feedback loop" section. Runs the whole tiled 784-64-10 MLP end to end:
  * layer 1's 50 passes (25 input-tiles x 2 output-tiles), a hidden-buffer
  * commit, then layer 2's 2 passes (2 input-tiles x 1 output-tile),
  * accumulating each output-tile's partial sum *outside* the VMM (the VMM
  * never re-accumulates across passes -- see Vmm.scala), then bias + ReLU
  * + requant (layer 1) or bias + argmax (layer 2).
  *
  * This is a first correctness-first cut, not yet the Phase 5-hardened
  * version -- see "Known skeleton shortcuts" below. Phase 4 (full 16-image
  * golden sweep against [[MlpRefModel]], latency report) is still open;
  * `MlpEngineSpec` here only smoke-tests one golden image end to end.
  *
  * ==Loading (all three are plain streaming ports, edge-detected pulses)==
  * PLAN.md's diagram calls the weight path "WMC" and suggests `$readmemh`
  * for a first sim-only fill; this skeleton instead gives the weights/bias
  * their own runtime Decoupled ports, same shape as `VmmController.weightRow`,
  * so the whole engine is pokeable in chiseltest without depending on
  * Verilog `$readmemh` / treadle file-loading semantics. Swap to
  * `loadMemoryFromFileInline` later if a from-bitstream weight ROM is
  * wanted (Phase 5).
  *
  *   - `weightLoad` (pulse) + `weightRow` (`Decoupled(Vec(Dim, SInt))`):
  *     exactly `(Layer1.passes + Layer2.passes) * Dim` beats, one weight
  *     row per beat, in tile-major/row-major order -- layer 1's 50 tiles
  *     (`o` outer, `k` inner, matching `manifest.json`), then layer 2's 2,
  *     each tile's 32 rows in order. Identical contract to
  *     `model/artifacts/layer{1,2}_w.hex` read row-wise (see
  *     `MlpRefModel.readWeightHex`), just fed live instead of parsed from
  *     hex text.
  *   - `biasLoad` (pulse) + `biasWord` (`Decoupled(SInt(MaxPsumWidth.W))`):
  *     exactly `Layer1.n + Layer2.nPadded` (64 + 32 = 96) beats -- layer 1's
  *     64 real biases, then layer 2's 10 real + 22 don't-care padding
  *     beats (never read back). Every value is sign-extended to
  *     `MaxPsumWidth` (18) bits by the caller, even layer 2's, which is
  *     natively narrower (`Layer2.psumWidth` = 14) -- the engine adds bias
  *     at a uniform 18-bit width for both layers to avoid a second,
  *     narrower accumulator path.
  *   - `imgLoad` (pulse) + `imgPixel` (`Decoupled(SInt(InWidth.W))`):
  *     exactly `Layer1.k` (784) beats, one pixel per beat. The last
  *     input-tile's 16-pixel pad (784..799) is never written and reads as
  *     0 because the activation register resets to 0 -- no explicit pad
  *     beats needed.
  *
  * All three loads use the same stall-timeout guard as `VmmController`
  * (`loadTimeout` consecutive stalled cycles -> sticky `error`).
  *
  * ==Inference==
  * Pulse `start` (with weights, bias and an image already loaded); `busy`
  * stays high until `done` pulses one cycle with `cls` (argmax over the 10
  * real logits) and `logits` (the raw, pre-argmax INT`Layer2.psumWidth`
  * values) valid. `error` is sticky (mirrors `VmmController.error`) and
  * clears on the next `loadStart`/`biasLoad`/`imgLoad`/`start` pulse.
  *
  * ==Known skeleton shortcuts (flagged for Phase 5 hardening)==
  *   - The activation store is banked per input-tile (`actBanks`, 25 x
  *     `Dim`-wide) and the weight store is one flat, scalar-element `Mem`
  *     per lane (`weightMems`, `Dim` x `WeightBeats`-deep) rather than a
  *     single `Mem` of `Vec(Dim, SInt)` -- both shapes exist specifically
  *     because the naive version of each (one flat `Dim*inputTiles`-deep
  *     dynamically-indexed `Reg(Vec(...))`, and one aggregate-typed `Mem`)
  *     measured **single-digit seconds per cycle** under chiseltest's
  *     treadle backend: the classic FIRRTL compiler this project targets
  *     (see Main.scala) doesn't lower an aggregate-element `Mem` to a real
  *     memory primitive, so it was expanding into a per-element register
  *     array plus a huge read mux, and treadle re-evaluates that mux, as
  *     part of the always-live top-level `ctrl.io.a` / `ctrl.io.weightRow`
  *     wiring, every single cycle regardless of state. Correctness-wise
  *     the naive shapes were fine; this is purely a size/speed fix, and
  *     the resulting flat-per-lane `Mem`s are also just better hardware
  *     (real BRAM/LUTRAM inference candidates) for Phase 5 to check.
  *   - The weight/bias stores are combinational-read `Mem` (LUTRAM/register
  *     inference), not `SyncReadMem` (BRAM) -- simplest correct thing for a
  *     first cut; PLAN.md's Phase 5 already expects to "check it still fits
  *     xc7z020" after this module exists.
  *   - `weightRow`/`biasWord`/`imgPixel` are held streaming continuously by
  *     this engine's own FSM (never gapped mid-tile), so the inner
  *     `VmmController`'s own `loadTimeout` can never trip from *our* side;
  *     it only guards against `VmmController`'s internal contract being
  *     broken, same as it always did.
  *
  * @param loadTimeout    consecutive stalled cycles on any of the three
  *                       loader ports before that load aborts into `error`
  *                       (passed straight through to the internal
  *                       `VmmController` too).
  * @param computeTimeout passed straight through to the internal
  *                       `VmmController` (`0` = its default).
  */
class MlpEngine(
  val loadTimeout: Int = 1024,
  val computeTimeout: Int = 0,
) extends Module {

  // Bias is added at a single, uniform width for both layers (the wider of
  // the two) so there is only one accumulate-bias-and-finalize datapath.
  private val MaxPsumWidth = math.max(Layer1.psumWidth, Layer2.psumWidth)
  require(MaxPsumWidth == Layer1.psumWidth, "layer 1 is expected to have the wider psum")

  private val WeightBeats = (Layer1.passes + Layer2.passes) * Dim // 1664
  private val BiasBeats   = Layer1.n + Layer2.nPadded             // 96
  private val ImgBeats    = Layer1.k                              // 784

  private val beatW = log2Ceil(WeightBeats) // widest of the three counters
  private val rowW  = log2Ceil(Dim)
  private val kW    = log2Ceil(Layer1.inputTiles)
  private val clsW  = log2Ceil(Layer2.n)

  val io = IO(new Bundle {
    val weightLoad = Input(Bool())
    val weightRow  = Flipped(Decoupled(Vec(Dim, SInt(InWidth.W))))

    val biasLoad = Input(Bool())
    val biasWord = Flipped(Decoupled(SInt(MaxPsumWidth.W)))

    val imgLoad  = Input(Bool())
    val imgPixel = Flipped(Decoupled(SInt(InWidth.W)))

    val start = Input(Bool())

    val busy  = Output(Bool())
    val done  = Output(Bool())
    val error = Output(Bool())
    val cls   = Output(UInt(clsW.W))
    val logits = Output(Vec(Layer2.n, SInt(Layer2.psumWidth.W)))
  })

  val ctrl = Module(new VmmController(VmmParams(dim = Dim, inWidth = InWidth), loadTimeout, computeTimeout))

  // ---------------------------------------------------------------------
  // Storage
  // ---------------------------------------------------------------------
  // Weight rows, flat-addressed by global beat index (0 until WeightBeats):
  // layer 1's 50 tiles (o outer, k inner) x 32 rows, then layer 2's 2 x 32.
  // Loading and reading during compute share this exact addressing scheme.
  // One flat, scalar-element `Mem` per lane (not a single `Mem` of
  // `Vec(Dim, SInt)`) -- an aggregate-typed `Mem` doesn't lower to a real
  // memory primitive on the classic FIRRTL compiler backend this project
  // uses (see Main.scala), so it was instead expanding into a per-element
  // register array plus a WeightBeats-deep mux replicated Dim ways: the
  // same shape of blowup `actBanks` fixed for the activation store, just
  // on the weight-load side, and just as fatal for both simulator speed
  // and the LUT count Phase 5 will eventually check.
  val weightMems = Seq.fill(Dim)(Mem(WeightBeats, SInt(InWidth.W)))
  // Bias, flat-addressed 0 until BiasBeats: layer 1's 64, then layer 2's 32
  // (10 real + 22 padding, never read back). Sign-extended to MaxPsumWidth
  // by the caller (see class doc).
  val biasMem = Mem(BiasBeats, SInt(MaxPsumWidth.W))
  // Unified activation store, banked per input-tile (Layer1.inputTiles = 25
  // banks of Dim lanes) rather than one flat Layer1.kPadded-deep register
  // file: reading the tile the compute path needs is then one `Dim`-wide
  // mux selected by `kReg` (Vec-of-Vec indexing), not `Dim` independent
  // deep dynamic muxes each covering the whole activation store -- the
  // earlier flat version measured ~6s/cycle under treadle (a 32-lane x
  // 784-deep mux re-evaluated as an unconditional top-level default every
  // cycle of the whole simulation), which this banking fixes by shrinking
  // that mux to Dim-wide x inputTiles-deep. Image pixels fill banks
  // 0..24 (bank Layer1.inputTiles-1 partially, rest 0 pad) for layer 1;
  // banks 0..1 are overwritten with the hidden layer for layer 2 once
  // layer 1 fully completes (both output-tiles read the whole image, so the
  // overwrite must wait until then -- see hiddenReg below).
  val actBanks = RegInit(VecInit(Seq.fill(Layer1.inputTiles)(VecInit(Seq.fill(Dim)(0.S(InWidth.W))))))
  val hiddenReg = Reg(Vec(Layer1.n, SInt(InWidth.W)))
  val logitsReg = Reg(Vec(Layer2.n, SInt(Layer2.psumWidth.W)))
  val clsReg    = RegInit(0.U(clsW.W))
  // Cross-input-tile partial-sum buffer for the *current* output-tile, one
  // lane per VMM column. Sized to the wider layer (18b); layer 2 just uses
  // the low bits of the same lanes.
  val psumReg = RegInit(VecInit(Seq.fill(Dim)(0.S(MaxPsumWidth.W))))

  val errReg = RegInit(false.B)

  // ---------------------------------------------------------------------
  // Tile-scheduler state
  // ---------------------------------------------------------------------
  val layerReg = RegInit(0.U(1.W)) // 0 = layer 1, 1 = layer 2
  val oReg     = RegInit(0.U(1.W)) // output-tile index (Layer1 has 2, Layer2 has 1)
  val kReg     = RegInit(0.U(kW.W)) // input-tile index within the current output-tile
  val rowCtr   = RegInit(0.U(rowW.W))
  val beatCtr  = RegInit(0.U(beatW.W))
  val loadStall = RegInit(0.U(log2Ceil(loadTimeout + 1).W))

  val curInputTiles = Mux(layerReg === 0.U, Layer1.inputTiles.U, Layer2.inputTiles.U)

  val sIdle :: sLoadW :: sLoadB :: sLoadImg :: sTileStart :: sFeedW :: sCompStart :: sCompWait :: sPostTile :: sCommitHidden :: sArgmax :: Nil = Enum(11)
  val state = RegInit(sIdle)

  // Rising-edge-detect the four "go" strobes (same reasoning as VmmController:
  // a level held high for several cycles must start exactly one operation).
  val weightLoadPrev = RegNext(io.weightLoad, false.B)
  val biasLoadPrev   = RegNext(io.biasLoad, false.B)
  val imgLoadPrev    = RegNext(io.imgLoad, false.B)
  val startPrev      = RegNext(io.start, false.B)
  val weightLoadPulse = io.weightLoad && !weightLoadPrev
  val biasLoadPulse   = io.biasLoad && !biasLoadPrev
  val imgLoadPulse    = io.imgLoad && !imgLoadPrev
  val startPulse      = io.start && !startPrev

  // Global weight-tile index for the current (layer, o, k), matching the
  // loader's tile-major order (layer 1's 50 tiles, then layer 2's 2).
  private val layer1Tiles = Layer1.outputTiles * Layer1.inputTiles
  val tileBase = Mux(layerReg === 0.U,
    oReg * Layer1.inputTiles.U + kReg,
    layer1Tiles.U + (oReg * Layer2.inputTiles.U + kReg))

  // ---- fixed-point helpers, bit-exact mirrors of MlpRefModel/quant.py ----
  private def roundShiftHw(x: SInt, s: Int): SInt = {
    require(s >= 0)
    if (s == 0) x
    else {
      val half = BigInt(1) << (s - 1)
      (x +& half.S((s + 1).W)) >> s
    }
  }
  private def clampInt4Hw(x: SInt): SInt = {
    val hi = Int4Max.S
    val lo = Int4Min.S
    val c  = Mux(x > hi, hi, Mux(x < lo, lo, x))
    c(InWidth - 1, 0).asSInt
  }

  // ---- defaults ----
  ctrl.io.loadStart    := false.B
  ctrl.io.weightRow.valid := false.B
  ctrl.io.weightRow.bits  := VecInit(Seq.tabulate(Dim)(col => weightMems(col).read(tileBase * Dim.U + rowCtr)))
  ctrl.io.computeStart := false.B
  ctrl.io.a            := actBanks(kReg)

  io.weightRow.ready := false.B
  io.biasWord.ready   := false.B
  io.imgPixel.ready   := false.B
  io.busy  := state =/= sIdle
  io.done  := false.B
  io.error := errReg
  io.cls   := clsReg
  io.logits := logitsReg

  switch(state) {
    is(sIdle) {
      when(startPulse) {
        layerReg := 0.U
        oReg     := 0.U
        kReg     := 0.U
        for (i <- 0 until Dim) psumReg(i) := 0.S
        errReg := false.B
        state  := sTileStart
      }.elsewhen(weightLoadPulse) {
        beatCtr   := 0.U
        loadStall := 0.U
        errReg    := false.B
        state     := sLoadW
      }.elsewhen(biasLoadPulse) {
        beatCtr   := 0.U
        loadStall := 0.U
        errReg    := false.B
        state     := sLoadB
      }.elsewhen(imgLoadPulse) {
        beatCtr   := 0.U
        loadStall := 0.U
        errReg    := false.B
        state     := sLoadImg
      }
    }

    is(sLoadW) {
      io.weightRow.ready := true.B
      when(io.weightRow.fire) {
        for (col <- 0 until Dim) weightMems(col).write(beatCtr, io.weightRow.bits(col))
        beatCtr   := beatCtr + 1.U
        loadStall := 0.U
        when(beatCtr === (WeightBeats - 1).U) { state := sIdle }
      }.otherwise {
        loadStall := loadStall + 1.U
        when(loadStall === (loadTimeout - 1).U) { errReg := true.B; state := sIdle }
      }
    }

    is(sLoadB) {
      io.biasWord.ready := true.B
      when(io.biasWord.fire) {
        biasMem.write(beatCtr, io.biasWord.bits)
        beatCtr   := beatCtr + 1.U
        loadStall := 0.U
        when(beatCtr === (BiasBeats - 1).U) { state := sIdle }
      }.otherwise {
        loadStall := loadStall + 1.U
        when(loadStall === (loadTimeout - 1).U) { errReg := true.B; state := sIdle }
      }
    }

    is(sLoadImg) {
      io.imgPixel.ready := true.B
      when(io.imgPixel.fire) {
        // Dim is a power of two, so splitting the flat pixel index into
        // (bank, lane) is a plain bit-slice, not a division.
        actBanks(beatCtr >> rowW.U)(beatCtr(rowW - 1, 0)) := io.imgPixel.bits
        beatCtr   := beatCtr + 1.U
        loadStall := 0.U
        when(beatCtr === (ImgBeats - 1).U) { state := sIdle }
      }.otherwise {
        loadStall := loadStall + 1.U
        when(loadStall === (loadTimeout - 1).U) { errReg := true.B; state := sIdle }
      }
    }

    is(sTileStart) {
      // One-cycle loadStart pulse into the controller for this tile;
      // ctrl edge-detects it the same way VmmController's own doc describes.
      ctrl.io.loadStart := true.B
      rowCtr := 0.U
      state  := sFeedW
    }

    is(sFeedW) {
      // Held continuously (valid high every cycle) so ctrl's own loadTimeout
      // can never trip from our side -- see class doc.
      ctrl.io.weightRow.valid := true.B
      ctrl.io.weightRow.bits  := VecInit(Seq.tabulate(Dim)(col => weightMems(col).read(tileBase * Dim.U + rowCtr)))
      when(ctrl.io.weightRow.fire) {
        rowCtr := rowCtr + 1.U
        when(rowCtr === (Dim - 1).U) { state := sCompStart }
      }
    }

    is(sCompStart) {
      // Redundant with the top-level default (same expression) -- kept
      // explicit for readability at the point a[] actually matters.
      ctrl.io.computeStart := true.B
      ctrl.io.a := actBanks(kReg)
      state := sCompWait
    }

    is(sCompWait) {
      when(ctrl.io.done) {
        for (col <- 0 until Dim) psumReg(col) := psumReg(col) +& ctrl.io.y(col)
        when(kReg < (curInputTiles - 1.U)) {
          kReg  := kReg + 1.U
          state := sTileStart
        }.otherwise {
          state := sPostTile
        }
      }.elsewhen(ctrl.io.error) {
        errReg := true.B
        state  := sIdle
      }
    }

    is(sPostTile) {
      when(layerReg === 0.U) {
        when(oReg === 0.U) {
          for (col <- 0 until Dim) {
            val withBias = psumReg(col) +& biasMem.read(col.U)
            val relued   = Mux(withBias < 0.S, 0.S(withBias.getWidth.W), withBias)
            hiddenReg(col) := clampInt4Hw(roundShiftHw(relued, Fxp.requantShift))
          }
          oReg := 1.U
          kReg := 0.U
          for (i <- 0 until Dim) psumReg(i) := 0.S
          state := sTileStart
        }.otherwise {
          for (col <- 0 until Dim) {
            val withBias = psumReg(col) +& biasMem.read((Dim + col).U)
            val relued   = Mux(withBias < 0.S, 0.S(withBias.getWidth.W), withBias)
            hiddenReg(Dim + col) := clampInt4Hw(roundShiftHw(relued, Fxp.requantShift))
          }
          state := sCommitHidden
        }
      }.otherwise {
        for (col <- 0 until Layer2.n) {
          val withBias = psumReg(col) +& biasMem.read((Layer1.n + col).U)
          logitsReg(col) := withBias(Layer2.psumWidth - 1, 0).asSInt
        }
        state := sArgmax
      }
    }

    is(sCommitHidden) {
      // hiddenReg's 64 entries fill banks 0 and 1 exactly (Layer1.n == 2*Dim).
      for (i <- 0 until Dim) {
        actBanks(0)(i) := hiddenReg(i)
        actBanks(1)(i) := hiddenReg(Dim + i)
      }
      layerReg := 1.U
      oReg     := 0.U
      kReg     := 0.U
      for (i <- 0 until Dim) psumReg(i) := 0.S
      state := sTileStart
    }

    is(sArgmax) {
      var bestVal = logitsReg(0)
      var bestIdx = 0.U(clsW.W)
      for (i <- 1 until Layer2.n) {
        val better = logitsReg(i) > bestVal
        bestVal = Mux(better, logitsReg(i), bestVal)
        bestIdx = Mux(better, i.U(clsW.W), bestIdx)
      }
      clsReg  := bestIdx
      // clsReg (like VmmController's yReg) only lands on the *next* clock
      // edge, but `done` needs to be trustworthy on this same cycle -- so
      // also drive io.cls combinationally with the just-computed value
      // here, overriding the `io.cls := clsReg` default. Without this, a
      // reader sampling on the done pulse (as MlpEngineSpec does) sees the
      // previous inference's (or the RegInit) stale class instead of this
      // one's -- found the hard way, by actually simulating (Verilator; see
      // MlpEngineSpec's doc comment).
      io.cls  := bestIdx
      io.done := true.B
      state   := sIdle
    }
  }
}
