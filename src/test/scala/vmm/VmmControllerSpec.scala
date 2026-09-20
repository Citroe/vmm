package vmm

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random

/** Drives only the [[VmmController]] high-level handshake (loadStart /
  * weightRow / computeStart / a / busy / done / y) and checks the result
  * against a plain-Scala golden model. If these pass, the FSM is correctly
  * sequencing the underlying [[Vmm]] core's weight-load / aValid-pulse /
  * yValid-wait protocol end to end.
  */
class VmmControllerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  def golden(a: Seq[Int], w: Seq[Seq[Int]]): Seq[Int] = {
    val dim = a.length
    (0 until dim).map(c => (0 until dim).map(r => a(r) * w(r)(c)).sum)
  }

  def randMat(dim: Int, inWidth: Int, rnd: Random): Seq[Seq[Int]] = {
    val range = 1 << inWidth; val bias = 1 << (inWidth - 1)
    Seq.fill(dim, dim)(rnd.nextInt(range) - bias)
  }

  def randVec(dim: Int, inWidth: Int, rnd: Random): Seq[Int] = {
    val range = 1 << inWidth; val bias = 1 << (inWidth - 1)
    Seq.fill(dim)(rnd.nextInt(range) - bias)
  }

  /** Pulse loadStart, then hand the controller one row per accepted beat,
    * honouring weightRow.ready. `stallEach` inserts idle (valid-low) cycles
    * before every row to exercise a stalling weight source. */
  def loadWeights(c: VmmController, w: Seq[Seq[Int]], stallEach: Int = 0): Unit = {
    val p = c.p
    c.io.loadStart.poke(true.B)
    c.clock.step(1)
    c.io.loadStart.poke(false.B)

    for (r <- 0 until p.dim) {
      if (stallEach > 0) {
        c.io.weightRow.valid.poke(false.B)
        c.clock.step(stallEach)
      }
      for (col <- 0 until p.dim) c.io.weightRow.bits(col).poke(w(r)(col).S)
      c.io.weightRow.valid.poke(true.B)
      while (!c.io.weightRow.ready.peek().litToBoolean) c.clock.step(1)
      c.clock.step(1) // the beat lands on this edge (ready && valid)
    }
    c.io.weightRow.valid.poke(false.B)
    c.io.busy.expect(false.B, "controller should be idle once the last row is accepted")
  }

  /** Pulse computeStart with `a`, poll for done, return y. */
  def compute(c: VmmController, a: Seq[Int]): Seq[BigInt] = {
    val p = c.p
    for (i <- 0 until p.dim) c.io.a(i).poke(a(i).S)
    c.io.computeStart.poke(true.B)
    c.clock.step(1)
    c.io.computeStart.poke(false.B)

    var cycles = 0
    while (!c.io.done.peek().litToBoolean && cycles < p.macLatency + 16) {
      c.clock.step(1); cycles += 1
    }
    c.io.done.peek().litToBoolean shouldBe true
    c.io.error.peek().litToBoolean shouldBe false
    val y = (0 until p.dim).map(i => c.io.y(i).peek().litValue)
    c.clock.step(1)
    y
  }

  behavior of "VmmController"

  it should "load a matrix and drive one compute pass to the right result (small dim)" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new VmmController(p)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val rnd = new Random(1)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)
      loadWeights(c, w)
      compute(c, a).map(_.toInt) shouldBe golden(a, w)
    }
  }

  it should "run several compute passes against one loaded matrix" in {
    val p = VmmParams(dim = 8, inWidth = 4)
    test(new VmmController(p)) { c =>
      val rnd = new Random(2)
      val w = randMat(p.dim, p.inWidth, rnd)
      loadWeights(c, w)
      for (_ <- 0 until 4) {
        val a = randVec(p.dim, p.inWidth, rnd)
        compute(c, a).map(_.toInt) shouldBe golden(a, w)
      }
    }
  }

  it should "reload the matrix and compute against the new weights" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new VmmController(p)) { c =>
      val rnd = new Random(3)
      val w1 = randMat(p.dim, p.inWidth, rnd)
      val w2 = randMat(p.dim, p.inWidth, rnd)
      val a  = randVec(p.dim, p.inWidth, rnd)
      loadWeights(c, w1)
      compute(c, a).map(_.toInt) shouldBe golden(a, w1)
      loadWeights(c, w2)
      compute(c, a).map(_.toInt) shouldBe golden(a, w2)
    }
  }

  it should "sequence the load correctly against a stalling weight source" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new VmmController(p)) { c =>
      val rnd = new Random(4)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)
      loadWeights(c, w, stallEach = 3)
      compute(c, a).map(_.toInt) shouldBe golden(a, w)
    }
  }

  it should "take exactly one operation per rising edge when the start strobe is held high (VIO-style level drive)" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new VmmController(p)) { c =>
      val rnd = new Random(5)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)

      // loadStart raised and never lowered until the load is well underway:
      // the FSM must consume the rising edge once, not restart every cycle.
      c.io.loadStart.poke(true.B)
      c.clock.step(1)
      for (r <- 0 until p.dim) {
        for (col <- 0 until p.dim) c.io.weightRow.bits(col).poke(w(r)(col).S)
        c.io.weightRow.valid.poke(true.B)
        while (!c.io.weightRow.ready.peek().litToBoolean) c.clock.step(1)
        c.clock.step(1)
      }
      c.io.weightRow.valid.poke(false.B)
      c.io.loadStart.poke(false.B)
      c.io.busy.expect(false.B)

      // Same for computeStart: hold it high across the whole pass. Exactly
      // one result, then idle -- no second pass kicked off by the still-high level.
      for (i <- 0 until p.dim) c.io.a(i).poke(a(i).S)
      c.io.computeStart.poke(true.B)
      var cycles = 0
      var seenDone = 0
      while (cycles < p.macLatency + 20) {
        if (c.io.done.peek().litToBoolean) seenDone += 1
        c.clock.step(1); cycles += 1
      }
      c.io.computeStart.poke(false.B)
      seenDone shouldBe 1
      c.io.error.peek().litToBoolean shouldBe false
      (0 until p.dim).map(i => c.io.y(i).peek().litValue.toInt) shouldBe golden(a, w)
    }
  }

  it should "latch a sticky error and return to idle if a weight load stalls and its rows never arrive" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new VmmController(p, loadTimeout = 8)) { c =>
      val rnd = new Random(10)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)

      // start a load, then never assert weightRow.valid
      c.io.weightRow.valid.poke(false.B)
      c.io.loadStart.poke(true.B)
      c.clock.step(1)
      c.io.loadStart.poke(false.B)

      // within loadTimeout (+ a couple) stalled cycles the load must abort
      var sawEarlyIdle = false
      for (_ <- 0 until 8) {
        if (!c.io.busy.peek().litToBoolean) sawEarlyIdle = true
        c.clock.step(1)
      }
      sawEarlyIdle shouldBe false // it should NOT have bailed before the timeout
      c.io.error.expect(true.B)
      c.io.busy.expect(false.B)

      // error is sticky until the next start
      c.clock.step(5)
      c.io.error.expect(true.B)

      // recovery: a well-formed load clears error, and a compute is correct
      loadWeights(c, w)
      c.io.error.expect(false.B)
      compute(c, a).map(_.toInt) shouldBe golden(a, w)
    }
  }

  it should "latch a sticky error if a compute pass never sees yValid (defensive timeout)" in {
    val p = VmmParams(dim = 8, inWidth = 4) // macLatency = 4
    // computeTimeout (2) < macLatency so a normal pass trips the defensive
    // sWait timeout branch before yValid ever arrives -- the only way to
    // exercise it, since the internal Vmm always answers on time.
    test(new VmmController(p, computeTimeout = 2)) { c =>
      val rnd = new Random(11)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)
      loadWeights(c, w) // load path is unaffected by computeTimeout

      for (i <- 0 until p.dim) c.io.a(i).poke(a(i).S)
      c.io.computeStart.poke(true.B)
      c.clock.step(1)
      c.io.computeStart.poke(false.B)

      var sawDone = false
      for (_ <- 0 until 12) {
        if (c.io.done.peek().litToBoolean) sawDone = true
        c.clock.step(1)
      }
      sawDone shouldBe false
      c.io.error.expect(true.B)
      c.io.busy.expect(false.B)

      // a fresh start clears the sticky flag
      c.io.computeStart.poke(true.B)
      c.clock.step(1)
      c.io.computeStart.poke(false.B)
      c.io.error.expect(false.B)
    }
  }

  it should "restart a load from row 0 when loadStart is re-strobed mid-load" in {
    val p = VmmParams(dim = 4, inWidth = 4)
    test(new VmmController(p)) { c =>
      val rnd  = new Random(12)
      val junk = randMat(p.dim, p.inWidth, rnd)
      val w    = randMat(p.dim, p.inWidth, rnd)
      val a    = randVec(p.dim, p.inWidth, rnd)

      // begin a load, feed 2 (wrong) rows, then re-strobe loadStart
      c.io.loadStart.poke(true.B)
      c.clock.step(1)
      c.io.loadStart.poke(false.B)
      c.io.weightRow.valid.poke(true.B)
      for (k <- 0 until 2) {
        for (col <- 0 until p.dim) c.io.weightRow.bits(col).poke(junk(k)(col).S)
        c.clock.step(1)
      }
      c.io.weightRow.valid.poke(false.B)
      c.io.loadStart.poke(true.B) // rising edge inside sLoad -> restart
      c.clock.step(1)
      c.io.loadStart.poke(false.B)

      // feed the real matrix in full from row 0
      c.io.weightRow.valid.poke(true.B)
      for (r <- 0 until p.dim) {
        for (col <- 0 until p.dim) c.io.weightRow.bits(col).poke(w(r)(col).S)
        while (!c.io.weightRow.ready.peek().litToBoolean) c.clock.step(1)
        c.clock.step(1)
      }
      c.io.weightRow.valid.poke(false.B)
      c.io.busy.expect(false.B)
      c.io.error.expect(false.B)

      compute(c, a).map(_.toInt) shouldBe golden(a, w)
    }
  }

  it should "drive the full target size (dim=32, inWidth=4, accWidth=13)" in {
    val p = VmmParams(dim = 32, inWidth = 4)
    p.accWidth shouldBe 13
    test(new VmmController(p)) { c =>
      val rnd = new Random(1234)
      val w = randMat(p.dim, p.inWidth, rnd)
      val a = randVec(p.dim, p.inWidth, rnd)
      loadWeights(c, w)
      compute(c, a).map(_.toInt) shouldBe golden(a, w)
    }
  }
}
