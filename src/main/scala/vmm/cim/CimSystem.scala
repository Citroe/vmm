package vmm.cim

import chisel3._
import chisel3.util._

import vmm.{Vmm, VmmParams}
import vmm.mlp.MlpConfig._

/** The system of docs/riscv-cim-diagram.pdf as far as it exists today:
  *
  * {{{
  *   host ──────────┐
  *                  ├─► Tcdm (4 banks) ◄──► CimAccel ◄──► Vmm (the array)
  *   MlpScheduler ──┘                          ▲
  *        └────────── register port ───────────┘
  * }}}
  *
  * `host` is a TCDM master port for whoever fills the memory -- the bring-up
  * harness now, the RISC-V core's data port later. [[MlpScheduler]] is the
  * temporary piece: it takes the place of the core's firmware, so
  * `start`/`busy`/`done`/`error`/`cls` are its ports and go away with it.
  *
  * To run an inference: have the parameters and an image in the TCDM at the
  * [[CimMap]] addresses (from `initFiles`, or written through `host`),
  * pulse `start`, wait for `done`. The logits and the hidden activations
  * are left in the TCDM.
  *
  * @param initFiles one `$readmemh` image per TCDM bank, or empty
  */
class CimSystem(initFiles: Seq[String] = Nil) extends Module {
  private val p = VmmParams(dim = Dim, inWidth = InWidth)

  val io = IO(new Bundle {
    val host = Flipped(new TcdmPort)

    val start = Input(Bool())
    val busy  = Output(Bool())
    val done  = Output(Bool())
    val error = Output(Bool())
    val cls   = Output(UInt(log2Ceil(Layer2.n).W))
  })

  val tcdm  = Module(new Tcdm(3, initFiles))
  val accel = Module(new CimAccel)
  val array = Module(new Vmm(p))
  val sched = Module(new MlpScheduler)

  tcdm.io.m(0) <> io.host
  tcdm.io.m(1) <> accel.io.tcdm
  tcdm.io.m(2) <> sched.io.tcdm

  array.io <> accel.io.core

  accel.io.reg   <> sched.io.reg
  sched.io.accDone  := accel.io.done
  sched.io.accError := accel.io.error

  sched.io.start := io.start
  io.busy  := sched.io.busy
  io.done  := sched.io.done
  io.error := sched.io.error
  io.cls   := sched.io.cls
}
