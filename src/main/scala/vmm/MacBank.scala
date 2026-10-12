package vmm

import chisel3._
import chisel3.util.log2Ceil

/** dim x dim fully-spatial, LUT-based MAC array: every weight is held in a
  * register (not a synchronous memory), every one of the dim x dim
  * products is computed in parallel from one registered activation
  * vector, and each column's dim-term sum is reduced by a pipelined
  * binary adder tree (one register per level) rather than the old
  * design's row-at-a-time streaming accumulation.
  *
  * This replaces the prior time-multiplexed design (dim physical MAC
  * units reused sequentially over dim contraction steps, weights held in
  * a SyncReadMem) now that dim/inWidth have shrunk enough (32x32 INT4)
  * that a fully-spatial array fits LUT fabric without DSPs -- see the
  * project README for the resource tradeoff this depends on (32x32 *and*
  * INT4 together, not either alone).
  *
  * Latency is a fixed `p.macLatency` (= log2Ceil(dim) + 1) cycles from
  * `start` to `valid`: one cycle to register the dim x dim products, plus
  * one register stage per adder-tree level (log2Ceil(dim) levels). This
  * is constant in dim, replacing the old design's linear `dim` cycles.
  * Widths grow with the data: products are prodWidth bits and each
  * adder-tree level adds one bit, reaching accWidth at the last level
  * (see VmmParams), so every sum is exact with no bit carried that the
  * data cannot use.
  *
  * `start` must be a single-cycle pulse and must not be asserted again
  * until `valid` has pulsed for the prior pass -- kept as a deliberate
  * interface restriction (matching the old design and what a future
  * controller expects) even though this datapath has no shared/reused
  * resource that would force it structurally; see Vmm, which
  * edge-detects `aValid` into exactly that pulse.
  *
  * `weightLoad` racing an in-flight pass: the products a pass uses are the
  * ones registered from `weightMem` on the cycle `start` is asserted,
  * before that cycle's `weightLoad` write (if any) takes effect -- so an
  * in-flight pass always finishes against the weights that were resident
  * when it started, and a concurrent `weightLoad` only affects the next
  * pass (later products do change with the new weights, but they trail
  * the pass through the pipeline and never reach the enabled `y` stage). See VmmSpec for a regression
  * test of this.
  */
class MacBank(p: VmmParams) extends Module {
  val io = IO(new Bundle {
    val weightLoad    = Input(Bool())
    val weightRow     = Input(UInt(log2Ceil(p.dim).W))
    val weightRowData = Input(Vec(p.dim, SInt(p.inWidth.W)))

    val start = Input(Bool())
    val a     = Input(Vec(p.dim, SInt(p.inWidth.W)))
    val valid = Output(Bool())
    val y     = Output(Vec(p.dim, SInt(p.accWidth.W)))
  })

  // Dense register-grid weight store: every weight must be readable
  // combinationally every cycle a pass is active (fully spatial), unlike
  // the old design's single-row-at-a-time SyncReadMem. At dim=32/inWidth=4
  // this is 32*32*4 = 4096 bits total, small enough for registers/LUTRAM
  // without concern.
  val weightMem = RegInit(VecInit.fill(p.dim, p.dim)(0.S(p.inWidth.W)))
  when(io.weightLoad) {
    weightMem(io.weightRow) := io.weightRowData
  }

  // Stage 0->1: register all dim x dim products at their natural
  // prodWidth (exact: INT_inWidth x INT_inWidth never needs more).
  //
  // The product and intermediate adder-tree registers are free-running: no
  // clock enable, no reset. A single flip-flop enabling thousands of
  // registers was the critical path (zero logic, ~9 ns of routing), and
  // nothing needs those registers to hold -- the `valid` chain alone marks
  // which cycle carries a pass's data, and only the final stage (which
  // drives `y`) is enabled, so `y` still holds between passes. `start` is
  // therefore just the head of the valid chain: the products sampled on
  // that cycle are the ones the pass's `valid` lines up with.
  val numLevels = log2Ceil(p.dim)
  val prodValid = RegNext(io.start, false.B)
  val prod = Reg(Vec(p.dim, Vec(p.dim, SInt(p.prodWidth.W))))
  when(io.start || (numLevels != 0).B) {
    for (r <- 0 until p.dim) {
      for (c <- 0 until p.dim) {
        prod(r)(c) := io.a(r) * weightMem(r)(c)
      }
    }
  }

  // Stage 1..macLatency-1: pipelined per-column binary adder tree, one
  // register stage per level, halving the term count each level
  // (dim -> dim/2 -> ... -> 1) and growing one bit per level
  // (prodWidth+1, ..., accWidth). Deliberately not merged with the
  // multiply stage above, and deliberately registered at every level
  // rather than left as one wide combinational reduction -- the old
  // design's unpipelined-wide-combinational accumulate is implicated in
  // its inherited timing-closure failure (see fpga/README.md), so this
  // avoids repeating that shape.
  var terms: Seq[Vec[SInt]] =
    (0 until p.dim).map(c => VecInit((0 until p.dim).map(r => prod(r)(c))))
  var levelValid = prodValid
  for (level <- 0 until numLevels) {
    val halfLen = terms.head.length / 2
    val nextValid = RegNext(levelValid, false.B)
    val nextTerms = Seq.fill(p.dim)(Reg(Vec(halfLen, SInt((p.prodWidth + level + 1).W))))
    // Only the last level holds its value (it is `y`).
    when(levelValid || (level != numLevels - 1).B) {
      for (c <- 0 until p.dim) {
        for (i <- 0 until halfLen) {
          nextTerms(c)(i) := terms(c)(2 * i) +& terms(c)(2 * i + 1)
        }
      }
    }
    terms = nextTerms
    levelValid = nextValid
  }

  io.y     := VecInit(terms.map(_.head))
  io.valid := levelValid
}
