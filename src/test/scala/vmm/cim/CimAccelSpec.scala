package vmm.cim

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import vmm.{Vmm, VmmParams}
import vmm.mlp.MlpConfig._
import vmm.mlp.MlpRefModel
import CimMap._
import TcdmDriver._

/** [[CimAccel]] with its array and a TCDM, the register port and one TCDM
  * master brought out for the test to play host. */
class CimAccelHarness extends Module {
  val io = IO(new Bundle {
    val host  = Flipped(new TcdmPort)
    val reg   = Flipped(new CimRegPort)
    val busy  = Output(Bool())
    val done  = Output(Bool())
    val error = Output(Bool())
  })
  val tcdm  = Module(new Tcdm(nMasters = 2))
  val accel = Module(new CimAccel)
  val array = Module(new Vmm(VmmParams(dim = Dim, inWidth = InWidth)))
  tcdm.io.m(0) <> io.host
  tcdm.io.m(1) <> accel.io.tcdm
  array.io <> accel.io.core
  accel.io.reg <> io.reg
  io.busy  := accel.io.busy
  io.done  := accel.io.done
  io.error := accel.io.error
}

/** Single jobs driven the way firmware will drive them -- data into the
  * TCDM, pointers and mode into the registers, START, wait -- checked
  * against plain integer arithmetic and `MlpRefModel`'s requantisation.
  * Verilator backend; run as described in `CimSystemSpec`.
  */
class CimAccelSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  private val rng = new scala.util.Random(0xc1a)
  private def int4(): Int = rng.nextInt(16) - 8

  private def vmm(x: Seq[Int], w: Seq[Seq[Int]]): Seq[Long] =
    (0 until Dim).map(col => (0 until Dim).map(row => x(row).toLong * w(row)(col)).sum)

  private def runJob(c: CimAccelHarness, mode: Int): Int = {
    regWrite(c.io.reg, c.clock, Regs.Mode, mode)
    regWrite(c.io.reg, c.clock, Regs.Ctrl, 1)
    var cycles = 0
    while (!c.io.done.peek().litToBoolean && cycles < 2000) { c.clock.step(1); cycles += 1 }
    c.io.done.expect(true.B, "job did not finish")
    c.io.error.expect(false.B)
    c.clock.step(1)
    c.io.busy.expect(false.B)
    c.io.reg.addr.poke(Regs.Ctrl.U)
    c.io.reg.rdata.expect(Status.Done.U)
    cycles
  }

  behavior of "CimAccel"

  it should "run load / bias / compute / store jobs and keep weights and accumulators between jobs" in {
    val w    = Seq.fill(Dim, Dim)(int4())
    val x1   = Seq.fill(Dim)(int4())
    val x2   = Seq.fill(Dim)(int4())
    // Mostly small enough that the requantised values spread over the INT4
    // range; two lanes at the extremes of an 18-bit bias for the widths.
    val bias = Seq((1L << 17) - 1, -(1L << 17)) ++ Seq.fill(Dim - 2)((rng.nextInt(2048) - 1024).toLong)

    val wAt = WeightBase + 3 * TileBytes // not the first tile, on purpose
    val x1At = ImgBase
    val x2At = ImgBase + 5 * RowWords * 4

    test(new CimAccelHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.clock.setTimeout(0)
      writeWords(c.io.host, c.clock, wAt, w.flatMap(pack))
      writeWords(c.io.host, c.clock, x1At, pack(x1))
      writeWords(c.io.host, c.clock, x2At, pack(x2))
      writeWords(c.io.host, c.clock, Bias1Base, bias.map(u32))

      regWrite(c.io.reg, c.clock, Regs.WPtr, wAt)
      regWrite(c.io.reg, c.clock, Regs.BPtr, Bias1Base)

      // 1. program the array, preload bias, one pass, raw result
      regWrite(c.io.reg, c.clock, Regs.XPtr, x1At)
      regWrite(c.io.reg, c.clock, Regs.YPtr, LogitsBase)
      runJob(c, Mode.LoadW | Mode.AccBias | Mode.Compute | Mode.StoreRaw)
      val acc1 = vmm(x1, w).zip(bias).map { case (y, b) => y + b }
      readWords(c.io.host, c.clock, LogitsBase, Dim).map(s32) shouldBe acc1

      // 2. second pass on the resident weights, accumulating on top of job
      //    1, then ReLU + requantise
      val shift = 7
      regWrite(c.io.reg, c.clock, Regs.Shift, shift)
      regWrite(c.io.reg, c.clock, Regs.XPtr, x2At)
      regWrite(c.io.reg, c.clock, Regs.YPtr, HiddenBase)
      runJob(c, Mode.Compute | Mode.StoreAct | Mode.Relu)
      val acc2 = acc1.zip(vmm(x2, w)).map { case (a, y) => a + y }
      val act2 = acc2.map(a => MlpRefModel.requantToInt4(math.max(0L, a), shift))
      act2.distinct.length should be > 2 // the stimulus really exercises the range
      unpack(readWords(c.io.host, c.clock, HiddenBase, RowWords)) shouldBe act2

      // 3. cleared accumulators, no ReLU: negative activations survive
      regWrite(c.io.reg, c.clock, Regs.Shift, 6)
      runJob(c, Mode.AccClr | Mode.Compute | Mode.StoreAct)
      val act3 = vmm(x2, w).map(MlpRefModel.requantToInt4(_, 6))
      act3.exists(_ < 0) shouldBe true
      unpack(readWords(c.io.host, c.clock, HiddenBase, RowWords)) shouldBe act3

      // 4. a store-only job reads the accumulators back out unchanged
      regWrite(c.io.reg, c.clock, Regs.YPtr, LogitsBase)
      runJob(c, Mode.StoreRaw)
      readWords(c.io.host, c.clock, LogitsBase, Dim).map(s32) shouldBe vmm(x2, w)
    }
  }
}
