package vmm

import chisel3._
import chisel3.util._

/** Sequencing FSM for [[Vmm]]: turns the core's low-level, timing-sensitive
  * protocol (assert `weightLoad` for exactly `dim` consecutive cycles, one row
  * per cycle; then a single-cycle `aValid` pulse; then wait a fixed
  * `macLatency + 1` cycles for the `yValid` pulse -- see Vmm.scala) into a
  * plain start/busy/done handshake a bus wrapper or a higher-level controller
  * can drive without cycle-counting.
  *
  * The array itself is not instantiated here: it is reached through the
  * `core` port, so the FSM and the array are separate modules (the array
  * stands in for the mixed-signal macro of docs/riscv-cim-diagram.pdf, this
  * FSM is part of the synthesized wrapper around it). [[VmmController]]
  * below bundles the two back together with the same IO as before.
  *
  * `loadStart` / `computeStart` are rising-edge-detected internally (same as
  * Vmm does for `aValid`), so a source that can only drive a level and not a
  * pulse -- a JTAG VIO output, or a memory-mapped "go" bit held for several
  * bus cycles -- triggers exactly one operation, not one per cycle it stays
  * high. `weightRow` is a normal Decoupled slave (`valid` held until `ready`),
  * not edge-detected -- a streaming DMA or bus wrapper feeding it works
  * unchanged; a level-only JTAG driver needs a one-cycle `valid` pulse per
  * row (see fpga/vmm_debug.v, which does that conversion).
  *
  * Weight load:
  *   - pulse (or raise, then lower) `loadStart` -- only the rising edge is
  *     taken. Taken from `sIdle` it starts a load; taken mid-load it
  *     restarts the current load from row 0 (an escape hatch for a driver
  *     that lost count).
  *   - then feed exactly `p.dim` rows, row 0 first, through the `weightRow`
  *     Decoupled port. `weightRow.ready` is high on every cycle the FSM is
  *     waiting for a row, so a stalling source (deasserting `valid`) is fine.
  *   - `busy` drops when the last row has been accepted.
  *   - if the source goes silent for `loadTimeout` consecutive cycles
  *     without delivering the next row, the load aborts: `error` latches and
  *     the FSM returns to `sIdle` rather than staying wedged with `busy`
  *     high. `loadTimeout` counts *stalled* cycles (it resets on every
  *     accepted row), so a slow-but-live producer never trips it -- the
  *     default (1024) only fires on a dead / miscounting master.
  *
  * Compute:
  *   - present the activation vector on `a` and pulse (or raise, then lower)
  *     `computeStart` -- only the rising edge, while idle, is taken
  *   - `a` is latched on that cycle, so it need not be held
  *   - `done` pulses for one cycle when `y` holds the settled result; `y` then
  *     holds until the next compute pass completes.
  *   - if `yValid` never arrives within `computeTimeout` cycles (defensive:
  *     the internal `Vmm` always answers within `macLatency + 1`, so this
  *     only fires if that contract is ever broken), `error` latches and the
  *     FSM returns to `sIdle`.
  *
  * `error` is a sticky flag meaning "the FSM could not complete the last
  * operation". It clears on the next `loadStart` / `computeStart` rising edge.
  *
  * @param loadTimeout    consecutive stalled cycles in the load state before
  *                       the load aborts into `error` (>= 2).
  * @param computeTimeout cycles to wait for `yValid` before a pass aborts
  *                       into `error`; `0` selects the default,
  *                       `p.macLatency + 4`.
  */
class VmmSequencer(
  val p: VmmParams = VmmParams(),
  val loadTimeout: Int = 1024,
  val computeTimeout: Int = 0,
) extends Module {
  require(loadTimeout >= 2, "loadTimeout must be >= 2 stalled cycles")
  require(computeTimeout >= 0, "computeTimeout must be >= 0 (0 = auto)")
  private val compTimeout: Int = if (computeTimeout > 0) computeTimeout else p.macLatency + 4

  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val weightRow = Flipped(Decoupled(Vec(p.dim, SInt(p.inWidth.W))))

    val computeStart = Input(Bool())
    val a            = Input(Vec(p.dim, SInt(p.inWidth.W)))

    val busy  = Output(Bool())
    val done  = Output(Bool())
    val error = Output(Bool())
    val y     = Output(Vec(p.dim, SInt(p.accWidth.W)))
  })
  val core = IO(Flipped(new VmmIO(p)))

  val sIdle :: sLoad :: sPulse :: sWait :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val rowCtr  = RegInit(0.U(log2Ceil(p.dim).W))
  // Consecutive cycles in sLoad with no accepted row; resets on every beat.
  val loadStall = RegInit(0.U(log2Ceil(loadTimeout + 1).W))
  // Cycles waited for yValid. Sized for both the normal latency and the
  // (possibly overridden) timeout.
  val waitCtr = RegInit(0.U(log2Ceil(math.max(p.macLatency + 8, compTimeout + 2)).W))

  val aReg   = RegInit(VecInit(Seq.fill(p.dim)(0.S(p.inWidth.W))))
  val yReg   = RegInit(VecInit(Seq.fill(p.dim)(0.S(p.accWidth.W))))
  val errReg = RegInit(false.B)

  // Rising-edge-detect the two "go" strobes: a level held high for several
  // cycles (VIO output, a wide bus write) must start exactly one operation.
  val loadStartPrev    = RegNext(io.loadStart, false.B)
  val computeStartPrev = RegNext(io.computeStart, false.B)
  val loadPulse    = io.loadStart && !loadStartPrev
  val computePulse = io.computeStart && !computeStartPrev

  // ---- core inputs: safe defaults, overridden per-state below ----
  core.weightLoad    := false.B
  core.weightRow     := rowCtr
  core.weightRowData := io.weightRow.bits
  core.aValid        := false.B
  core.a             := aReg

  // ---- controller outputs: defaults ----
  io.weightRow.ready := false.B
  io.busy  := state =/= sIdle
  io.done  := false.B
  io.error := errReg
  io.y     := yReg

  switch(state) {
    is(sIdle) {
      when(loadPulse) {
        rowCtr    := 0.U
        loadStall := 0.U
        errReg    := false.B
        state     := sLoad
      }.elsewhen(computePulse) {
        aReg   := io.a
        errReg := false.B
        state  := sPulse
      }
    }

    is(sLoad) {
      // Ready for a row every cycle we're in this state; forward an accepted
      // row straight into the core with the running row address.
      io.weightRow.ready := true.B
      when(loadPulse) {
        // Driver re-strobed loadStart mid-load -- restart from row 0.
        rowCtr    := 0.U
        loadStall := 0.U
      }.elsewhen(io.weightRow.fire) {
        core.weightLoad    := true.B
        core.weightRow     := rowCtr
        core.weightRowData := io.weightRow.bits
        rowCtr    := rowCtr + 1.U
        loadStall := 0.U
        when(rowCtr === (p.dim - 1).U) { state := sIdle }
      }.otherwise {
        // No beat this cycle. Abort if the source has been silent for
        // loadTimeout consecutive cycles -- otherwise a miscounted burst or
        // a dead master wedges the FSM here with busy stuck high.
        loadStall := loadStall + 1.U
        when(loadStall === (loadTimeout - 1).U) {
          errReg := true.B
          state  := sIdle
        }
      }
    }

    is(sPulse) {
      // One-cycle aValid pulse; Vmm edge-detects it into MacBank's start.
      // aReg has settled (latched a cycle earlier in sIdle), so the core
      // samples the right activation vector on this same edge.
      core.aValid := true.B
      waitCtr := 0.U
      state   := sWait
    }

    is(sWait) {
      waitCtr := waitCtr + 1.U
      when(core.yValid) {
        yReg    := core.y
        io.done := true.B
        io.y    := core.y
        state   := sIdle
      }.elsewhen(waitCtr === compTimeout.U) {
        errReg := true.B
        state  := sIdle
      }
    }
  }
}

/** [[VmmSequencer]] plus the [[Vmm]] array it drives, as one module -- the
  * self-contained top the standalone bring-up flow (fpga/vmm_debug.v,
  * sim/tb_vmm_controller.v) uses. See [[VmmSequencer]] for the protocol and
  * the parameters.
  */
class VmmController(
  val p: VmmParams = VmmParams(),
  val loadTimeout: Int = 1024,
  val computeTimeout: Int = 0,
) extends Module {
  val io = IO(new Bundle {
    val loadStart = Input(Bool())
    val weightRow = Flipped(Decoupled(Vec(p.dim, SInt(p.inWidth.W))))

    val computeStart = Input(Bool())
    val a            = Input(Vec(p.dim, SInt(p.inWidth.W)))

    val busy  = Output(Bool())
    val done  = Output(Bool())
    val error = Output(Bool())
    val y     = Output(Vec(p.dim, SInt(p.accWidth.W)))
  })

  val seq  = Module(new VmmSequencer(p, loadTimeout, computeTimeout))
  val core = Module(new Vmm(p))
  seq.io <> io
  core.io <> seq.core
}
