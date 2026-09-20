package vmm

import chisel3._
import chisel3.util.log2Ceil

/** Top-level VMM/CiM core: y = a^T * W, W stationary, dim x dim, inWidth-bit
  * signed in, full-scale-safe accWidth-bit accumulate out. No bus
  * (AXI-Lite/RoCC/TileLink) attached yet -- that's a wrapper around this
  * module, added once the integration target is settled.
  *
  * Weight load protocol: assert weightLoad with weightRow/weightRowData
  * for dim consecutive cycles (one matrix row per cycle) to load W.
  *
  * Compute protocol: present the activation vector on `a` and pulse (or
  * hold high, then lower -- only the rising edge matters) aValid. `yValid`
  * pulses a fixed `p.macLatency + 1` cycles later (see MacBank for the
  * pipeline breakdown), at which point `y` holds the settled result and
  * stays valid until the next aValid. Do not raise aValid again before
  * yValid: kept as a deliberate one-pass-at-a-time interface restriction
  * (see MacBank) even though the fully-spatial pipelined datapath has no
  * structural resource conflict that would force it -- revisit once a
  * controller wants back-to-back throughput.
  */
class Vmm(val p: VmmParams = VmmParams()) extends Module {
  val io = IO(new Bundle {
    val weightLoad    = Input(Bool())
    val weightRow     = Input(UInt(log2Ceil(p.dim).W))
    val weightRowData = Input(Vec(p.dim, SInt(p.inWidth.W)))

    val aValid = Input(Bool())
    val a      = Input(Vec(p.dim, SInt(p.inWidth.W)))

    val yValid = Output(Bool())
    val y      = Output(Vec(p.dim, SInt(p.accWidth.W)))
  })

  val bank = Module(new MacBank(p))

  bank.io.weightLoad    := io.weightLoad
  bank.io.weightRow     := io.weightRow
  bank.io.weightRowData := io.weightRowData

  // Edge-detect aValid into a single-cycle start pulse: MacBank's pipeline
  // would restart on every cycle aValid is held high, so a driver that
  // holds it high for several cycles (e.g. a VIO output, which is a level,
  // not a pulse) must not re-trigger MacBank on every one of those cycles.
  val aValidPrev = RegNext(io.aValid, false.B)
  val aPulse     = io.aValid && !aValidPrev

  val aReg = RegInit(VecInit(Seq.fill(p.dim)(0.S(p.inWidth.W))))
  when(aPulse) { aReg := io.a }
  bank.io.a := aReg

  // Delayed one cycle from aPulse so aReg already holds the new vector by
  // the time MacBank's multiply stage reads it.
  bank.io.start := RegNext(aPulse, false.B)

  io.yValid := bank.io.valid
  io.y      := bank.io.y
}
