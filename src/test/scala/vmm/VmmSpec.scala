package vmm

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random

class VmmSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  /** Reference model: y[c] = sum_r a(r) * w(r)(c), plain Scala Int math
    * (safe: worst case magnitude for dim<=1024, inWidth=8 is well under
    * Int range).
    */
  def golden(a: Seq[Int], w: Seq[Seq[Int]]): Seq[Int] = {
    val dim = a.length
    (0 until dim).map(c => (0 until dim).map(r => a(r) * w(r)(c)).sum)
  }

  def loadWeights(dut: Vmm, w: Seq[Seq[Int]]): Unit = {
    val p = dut.p
    dut.io.weightLoad.poke(true.B)
    for (r <- 0 until p.dim) {
      dut.io.weightRow.poke(r.U)
      for (c <- 0 until p.dim) dut.io.weightRowData(c).poke(w(r)(c).S)
      dut.clock.step(1)
    }
    dut.io.weightLoad.poke(false.B)
  }

  def runVector(dut: Vmm, a: Seq[Int]): Seq[BigInt] = {
    val p = dut.p
    dut.io.aValid.poke(true.B)
    for (r <- 0 until p.dim) dut.io.a(r).poke(a(r).S)
    dut.clock.step(1)
    dut.io.aValid.poke(false.B)

    var cycles = 0
    while (!dut.io.yValid.peek().litToBoolean && cycles < p.macLatency + 8) {
      dut.clock.step(1)
      cycles += 1
    }
    dut.io.yValid.peek().litToBoolean shouldBe true
    (0 until p.dim).map(c => dut.io.y(c).peek().litValue)
  }

  /** Signed range for an inWidth-bit value: [-2^(w-1), 2^(w-1)-1]. */
  def randMat(dim: Int, inWidth: Int, rnd: Random): Seq[Seq[Int]] = {
    val range = 1 << inWidth
    val bias  = 1 << (inWidth - 1)
    Seq.fill(dim, dim)(rnd.nextInt(range) - bias)
  }

  def randVec(dim: Int, inWidth: Int, rnd: Random): Seq[Int] = {
    val range = 1 << inWidth
    val bias  = 1 << (inWidth - 1)
    Seq.fill(dim)(rnd.nextInt(range) - bias)
  }

  behavior of "Vmm"

  it should "match a golden model on random matrices/vectors (small dim)" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new Vmm(p)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      val rnd = new Random(42)
      for (trial <- 0 until 5) {
        val w = randMat(p.dim, p.inWidth, rnd)
        val a = randVec(p.dim, p.inWidth, rnd)
        loadWeights(dut, w)
        val got = runVector(dut, a)
        val want = golden(a, w)
        got.map(_.toInt) shouldBe want
      }
    }
  }

  it should "match a golden model at a non-default width (parametricity check, inWidth=8)" in {
    val p = VmmParams(dim = 8, inWidth = 8)
    test(new Vmm(p)) { dut =>
      val rnd = new Random(99)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)
      loadWeights(dut, w)
      val got = runVector(dut, a)
      val want = golden(a, w)
      got.map(_.toInt) shouldBe want
    }
  }

  it should "handle the full-scale extreme (every tap at min*min) without overflow" in {
    val p = VmmParams(dim = 8, inWidth = 4)
    test(new Vmm(p)) { dut =>
      val minVal = -(1 << (p.inWidth - 1))
      val w = Seq.fill(p.dim, p.dim)(minVal)
      val a = Seq.fill(p.dim)(minVal)
      loadWeights(dut, w)
      val got = runVector(dut, a)
      val want = golden(a, w)
      got.map(_.toInt) shouldBe want
      // sanity: this is the literal worst case the accWidth sizing exists for
      want.head shouldBe (p.dim * minVal * minVal)
    }
  }

  it should "settle to the correct result and hold it (no re-accumulation) across idle cycles" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new Vmm(p)) { dut =>
      val rnd = new Random(7)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)
      loadWeights(dut, w)
      val got = runVector(dut, a)
      val want = golden(a, w)
      got.map(_.toInt) shouldBe want

      // hold for a bunch of extra cycles with no new aValid pulse: output
      // must stay exactly the same, not keep accumulating.
      for (_ <- 0 until 10) {
        dut.clock.step(1)
        (0 until p.dim).foreach(c => dut.io.y(c).peek().litValue.toInt shouldBe want(c))
      }
    }
  }

  it should "raise yValid at exactly macLatency + 1 cycles after aValid, not before or after" in {
    val p = VmmParams(dim = 8, inWidth = 4)
    test(new Vmm(p)) { dut =>
      val rnd = new Random(11)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)
      loadWeights(dut, w)

      dut.io.aValid.poke(true.B)
      for (r <- 0 until p.dim) dut.io.a(r).poke(a(r).S)
      dut.clock.step(1)
      dut.io.aValid.poke(false.B)

      // aPulse fires the cycle aValid is first sampled high; bank.io.start
      // is that delayed one more cycle (see Vmm.scala), so yValid should
      // rise exactly macLatency + 1 cycles after this point, not sooner.
      for (_ <- 0 until p.macLatency) {
        dut.io.yValid.peek().litToBoolean shouldBe false
        dut.clock.step(1)
      }
      dut.io.yValid.peek().litToBoolean shouldBe true
    }
  }

  it should "finish an in-flight pass against the pre-load weights, not a racing weightLoad" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new Vmm(p)) { dut =>
      val rnd = new Random(23)
      val wOld = randMat(p.dim, p.inWidth, rnd)
      val wNew = randMat(p.dim, p.inWidth, rnd)
      val a    = randVec(p.dim, p.inWidth, rnd)
      loadWeights(dut, wOld)

      // Start a pass against wOld: aPulse fires this cycle, but Vmm delays
      // bank.io.start one more cycle (RegNext(aPulse), so aReg has settled
      // first -- see Vmm.scala) before MacBank actually captures products
      // from weightMem. Race a weightLoad (row 0 of wNew) into *that*
      // cycle -- the one MacBank's start pulse actually lands on -- since
      // that's the edge where the read-old/write-new same-cycle guarantee
      // this test is checking actually applies. Poll for yValid every
      // cycle throughout: it's a single-cycle pulse, so anything that
      // steps the clock without polling (e.g. the dim-cycle loadWeights
      // helper) risks striding right past it.
      dut.io.aValid.poke(true.B)
      for (r <- 0 until p.dim) dut.io.a(r).poke(a(r).S)
      dut.clock.step(1)
      dut.io.aValid.poke(false.B)

      dut.io.weightLoad.poke(true.B)
      dut.io.weightRow.poke(0.U)
      for (c <- 0 until p.dim) dut.io.weightRowData(c).poke(wNew(0)(c).S)
      dut.clock.step(1)
      dut.io.weightLoad.poke(false.B)

      var cycles = 0
      while (!dut.io.yValid.peek().litToBoolean && cycles < p.macLatency + 8) {
        dut.clock.step(1)
        cycles += 1
      }
      dut.io.yValid.peek().litToBoolean shouldBe true
      val got = (0 until p.dim).map(c => dut.io.y(c).peek().litValue)
      val want = golden(a, wOld)
      got.map(_.toInt) shouldBe want

      // A subsequent pass, after fully loading wNew (including the row 0
      // this test raced in above), should see wNew.
      loadWeights(dut, wNew)
      val a2 = randVec(p.dim, p.inWidth, rnd)
      val got2 = runVector(dut, a2)
      val want2 = golden(a2, wNew)
      got2.map(_.toInt) shouldBe want2
    }
  }

  it should "work at the full target size (dim=32, inWidth=4, accWidth=13)" in {
    val p = VmmParams(dim = 32, inWidth = 4)
    p.accWidth shouldBe 13
    // 1024 PEs is well within treadle's reach now that the array is
    // fully-spatial rather than the old dim^2-combinational-scheduler
    // shape treadle struggled with -- no VerilatorBackendAnnotation needed.
    test(new Vmm(p)) { dut =>
      val rnd = new Random(1234)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)
      loadWeights(dut, w)
      val got = runVector(dut, a)
      val want = golden(a, w)
      got.map(_.toInt) shouldBe want
    }
  }
}
