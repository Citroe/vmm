package vmm.cim

import chisel3._
import chisel3.util._

import vmm.mlp.MlpConfig._
import CimMap._

/** Post-processing behind the array: one accumulator per column, summing
  * the array's `y` across passes (the array itself never accumulates), with
  * the bias folded in as the accumulators' starting value.
  *
  * The accumulators form a ring so everything that touches them one lane at
  * a time -- bias preload, raw readout, activation readout -- goes through
  * lane 0 / lane `Dim-1` only, and the ReLU / round-shift / clamp logic
  * exists once rather than per lane:
  *   - `load`: shift `loadData` in at the top; after `Dim` loads, word `i`
  *     sits in lane `i`.
  *   - `rotate`: move lane 0 to the top; after `Dim` rotates the ring is
  *     back where it started.
  *   - `raw` is always lane 0, sign-extended.
  *   - `act` is the INT4 activation (optional ReLU, arithmetic right shift
  *     by `shift` rounding half up, clamp -- the same arithmetic as
  *     `MlpRefModel.requantToInt4`) of the lane that was lane 0 in the
  *     previous cycle: the rounding add is registered, so the shift and
  *     clamp get a cycle of their own.
  * `clear`, `add`, and `load`/`rotate` are mutually exclusive.
  */
class PostProc extends Module {
  val io = IO(new Bundle {
    val clear    = Input(Bool())
    val add      = Input(Bool())
    val y        = Input(Vec(Dim, SInt(AccWidth.W)))
    val load     = Input(Bool())
    val loadData = Input(SInt(AccumWidth.W))
    val rotate   = Input(Bool())

    val relu  = Input(Bool())
    val shift = Input(UInt(5.W))
    val raw   = Output(UInt(32.W))
    val act   = Output(UInt(InWidth.W))
  })

  val acc = RegInit(VecInit(Seq.fill(Dim)(0.S(AccumWidth.W))))

  when(io.clear) {
    acc.foreach(_ := 0.S)
  }.elsewhen(io.add) {
    for (i <- 0 until Dim) acc(i) := acc(i) + io.y(i)
  }.elsewhen(io.load || io.rotate) {
    for (i <- 0 until Dim - 1) acc(i) := acc(i + 1)
    acc(Dim - 1) := Mux(io.load, io.loadData, acc(0))
  }

  val lane   = acc(0)
  val relued = Mux(io.relu && lane(AccumWidth - 1), 0.S, lane)
  // Rounding constant 2^(shift-1); a shift beyond the accumulator width
  // rounds everything to 0 or -1 either way.
  val half    = Mux(io.shift === 0.U, 0.U, (1.U << (io.shift - 1.U)(4, 0))(AccumWidth - 2, 0))
  val sumReg  = RegNext(relued +& half.zext)
  val rounded = sumReg >> io.shift
  val clamped = Mux(rounded > Int4Max.S, Int4Max.S, Mux(rounded < Int4Min.S, Int4Min.S, rounded))

  io.raw := lane.pad(32).asUInt
  io.act := clamped(InWidth - 1, 0)
}
