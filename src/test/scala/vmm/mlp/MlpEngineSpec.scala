package vmm.mlp

import java.io.{FileWriter, PrintWriter}
import java.nio.file.{Files, Path, Paths}

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import org.json4s._
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import MlpConfig._

/** Phase 3 verification: drive the top-level handshake (`weightLoad`/`weightRow`,
  * `biasLoad`/`biasWord`, `imgLoad`/`imgPixel`, `start`/`busy`/`done`) with the
  * real committed artefacts and check the RTL agrees with [[MlpRefModel]]
  * (already proven bit-exact against `model/refmodel.py` in `MlpRefModelSpec`)
  * on every committed golden image, end to end through both layers -- weights
  * and bias loaded once, then one `imgLoad` + inference per image, reusing
  * the same `MlpEngine` instance (also exercises that a second/third/...
  * inference on an already-loaded engine works, not just a from-reset one).
  *
  * '''Requires the Verilator backend, not the chiseltest default (treadle).'''
  * One inference drives the dim=32 `VmmController`/`Vmm` core (already
  * bit-blasted by the classic FIRRTL compiler this project targets, see
  * Main.scala) through 52 tile passes, ~2500 beats of weight/bias/image
  * streaming per image. Measured treadle cost: a single dim=32
  * `VmmController` load+compute pass alone (`VmmControllerSpec`/`VmmSpec`
  * "dim=32") takes ~3.5 minutes; extrapolated, one full `MlpEngine`
  * inference would be 1.5-2+ hours and the real per-cycle cost measured
  * during an attempted run (~20 s/beat once actual data was toggling, not
  * idling) pushed a full run to an estimated 1-2+ *days*. `VerilatorBackendAnnotation`
  * instead compiles the design (native sim, not interpreted): the same
  * inference runs in well under a second, ~10,000x faster.
  *
  * Verilator itself hit a real toolchain bug in this environment: g++
  * fails with `fatal error: VMlpEngine__pch.h.slow: No such file or
  * directory` even though the matching `.gch` exists alongside it -- a
  * known upstream Verilator/GCC precompiled-header issue (verilator/verilator
  * #4730, #4839, #5139, #5244, #5698: the generated Makefile `-include`s a
  * plain-named placeholder for GCC's automatic sibling-`.gch` lookup but
  * never creates that placeholder file). Worked around with a `g++` shim on
  * `PATH` (`.toolshim/g++`, gitignored -- not committed, environment-specific)
  * that creates the missing placeholder (symlinked to the real PCH source
  * header) after every PCH-generation invocation it sees. `ccache` (which
  * Verilator's Makefile always wraps the real compiler with here) will
  * *cache-hit* that PCH-generation step on a repeat run and skip invoking
  * the shim entirely, silently undoing the fix -- so `CCACHE_DISABLE=1` is
  * also required, every run, not just the first. Run it with:
  * {{{
  * PATH=./.toolshim:$PATH CCACHE_DISABLE=1 sbt "testOnly vmm.mlp.MlpEngineSpec"
  * }}}
  * Fixing this upstream in the actual Verilator/ccache install (or pinning
  * a Verilator version without the bug) would remove the need for both.
  *
  * This test itself already caught one real bug, only findable by actually
  * simulating the RTL: `sArgmax` wrote `clsReg` (a register, visible only
  * the *next* cycle) but pulsed `done` that same cycle, so `cls` read as
  * stale/reset (0) instead of the just-computed class -- fixed by also
  * driving `io.cls` combinationally in that state, the same idiom
  * `VmmController` already uses for `io.y`.
  */
private final case class MlpEngineGolden(index: Int, imageInt4: Array[Int])

class MlpEngineSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  // Progress log, bypassing sbt/ScalaTest's per-test stdout capture (which
  // buffers println until the test completes) -- this is the only way to
  // see this multi-hour-on-treadle test actually moving. Not meant to be
  // read by anything but a human tailing the file during a manual run.
  private val progressLog = new PrintWriter(new FileWriter("/tmp/mlp_engine_progress.log"), true)
  private def progress(msg: String): Unit = {
    progressLog.println(s"[${java.time.Instant.now()}] $msg")
    progressLog.flush()
  }

  private val artDir: Path = Paths.get("model", "artifacts")
  private def have(name: String): Boolean = Files.exists(artDir.resolve(name))
  private def haveArtifacts: Boolean =
    have("layer1_w.hex") && have("layer2_w.hex") &&
    have("layer1_b.hex") && have("layer2_b.hex") && have("golden.json")

  private def readGoldenImages(): Seq[MlpEngineGolden] = {
    implicit val fmt: Formats = DefaultFormats
    val text = new String(Files.readAllBytes(artDir.resolve("golden.json")), "UTF-8")
    val JArray(items) = parse(text)
    items.map(it => MlpEngineGolden((it \ "index").extract[Int], (it \ "image_int4").extract[Array[Int]]))
  }

  /** Pulse `weightLoad`, then stream every row of both layers' tiles,
    * o outer / k inner, matching the loader contract in MlpEngine's doc. */
  private def loadWeights(c: MlpEngine, p: MlpRefModel.Params): Unit = {
    c.io.weightLoad.poke(true.B)
    c.clock.step(1)
    c.io.weightLoad.poke(false.B)

    var beat = 0
    val totalBeats = (p.w1Tiles.length * p.w1Tiles(0).length + p.w2Tiles.length * p.w2Tiles(0).length) * Dim
    val t0 = System.nanoTime()
    def feedTiles(tiles: Array[Array[Array[Array[Int]]]]): Unit =
      for (o <- tiles.indices; k <- tiles(o).indices; row <- 0 until Dim) {
        for (col <- 0 until Dim) c.io.weightRow.bits(col).poke(tiles(o)(k)(row)(col).S)
        c.io.weightRow.valid.poke(true.B)
        while (!c.io.weightRow.ready.peek().litToBoolean) c.clock.step(1)
        c.clock.step(1)
        beat += 1
        if (beat % 100 == 0) {
          val rate = (System.nanoTime() - t0) / 1e9 / beat
          progress(f"weight beat $beat/$totalBeats (${rate}%.3f s/beat, ${rate * (totalBeats - beat)}%.0f s left this phase)")
        }
      }

    progress(s"loadWeights: starting, $totalBeats beats")
    feedTiles(p.w1Tiles)
    feedTiles(p.w2Tiles)
    progress(f"loadWeights: done, ${(System.nanoTime() - t0) / 1e9}%.1f s total")
    c.io.weightRow.valid.poke(false.B)
    c.io.busy.expect(false.B, "controller should be idle once the last weight row is accepted")
  }

  /** Pulse `biasLoad`, then stream layer 1's 64 real biases, layer 2's 10
    * real biases, and 22 don't-care padding beats (never read back). */
  private def loadBias(c: MlpEngine, p: MlpRefModel.Params): Unit = {
    c.io.biasLoad.poke(true.B)
    c.clock.step(1)
    c.io.biasLoad.poke(false.B)

    def feed(v: Long): Unit = {
      c.io.biasWord.bits.poke(v.S)
      c.io.biasWord.valid.poke(true.B)
      while (!c.io.biasWord.ready.peek().litToBoolean) c.clock.step(1)
      c.clock.step(1)
    }

    progress("loadBias: starting")
    p.b1Int.foreach(feed)
    p.b2Int.foreach(feed)
    for (_ <- 0 until (Layer2.nPadded - Layer2.n)) feed(0L)
    progress("loadBias: done")
    c.io.biasWord.valid.poke(false.B)
    c.io.busy.expect(false.B, "controller should be idle once the last bias word is accepted")
  }

  /** Pulse `imgLoad`, then stream the 784 real pixels (the last input-tile's
    * 16-pixel pad needs no beat -- the activation register resets to 0). */
  private def loadImage(c: MlpEngine, img: Array[Int]): Unit = {
    c.io.imgLoad.poke(true.B)
    c.clock.step(1)
    c.io.imgLoad.poke(false.B)

    progress("loadImage: starting")
    for (px <- img) {
      c.io.imgPixel.bits.poke(px.S)
      c.io.imgPixel.valid.poke(true.B)
      while (!c.io.imgPixel.ready.peek().litToBoolean) c.clock.step(1)
      c.clock.step(1)
    }
    progress("loadImage: done")
    c.io.imgPixel.valid.poke(false.B)
    c.io.busy.expect(false.B, "controller should be idle once the last pixel is accepted")
  }

  private def runInference(c: MlpEngine, maxCycles: Int = 20000): Int = {
    // Rough expected cycle count, for the ETA estimate below: 52 tile passes
    // (50 layer-1 + 2 layer-2), each ~sTileStart(1) + sFeedW(32) +
    // sCompStart(1) + sCompWait(macLatency+2) + sPostTile(1), plus a
    // handful of one-cycle housekeeping states.
    val expectedCycles = 52 * (1 + Dim + 1 + (log2Ceil(Dim) + 1 + 2) + 1) + 8
    progress(s"runInference: starting, expecting roughly $expectedCycles cycles")
    val t0 = System.nanoTime()
    c.io.start.poke(true.B)
    c.clock.step(1)
    c.io.start.poke(false.B)
    var cycles = 0
    while (!c.io.done.peek().litToBoolean && cycles < maxCycles) {
      c.clock.step(1); cycles += 1
      if (cycles % 100 == 0) {
        val elapsed = (System.nanoTime() - t0) / 1e9
        val rate = elapsed / cycles
        val etaLeft = rate * math.max(0, expectedCycles - cycles)
        progress(f"runInference: cycle $cycles/~$expectedCycles, elapsed ${elapsed}%.0f s, " +
          f"${rate}%.2f s/cycle, ~${etaLeft}%.0f s left if on track (error=${c.io.error.peek().litToBoolean})")
      }
    }
    progress(s"runInference: loop exited at cycle $cycles, done=${c.io.done.peek().litToBoolean}, " +
      s"error=${c.io.error.peek().litToBoolean}")
    c.io.done.peek().litToBoolean shouldBe true
    c.io.error.peek().litToBoolean shouldBe false
    // One more cycle so `state` actually lands back in sIdle before the
    // caller issues the next imgLoad/start pulse -- on the `done` cycle
    // itself the FSM is still finishing sArgmax, so a pulse asserted right
    // now would land a cycle too early and get silently dropped (same
    // "settle" step VmmControllerSpec's own compute() helper takes after
    // reading `y`, for the same reason). `cls`/`logits` are registers, so
    // they still read correctly after this extra step.
    c.clock.step(1)
    cycles
  }

  behavior of "MlpEngine (Phase 3 skeleton)"

  it should "run every golden image end to end and match MlpRefModel's logits and class" in {
    assume(haveArtifacts, "model/artifacts not fully present -- run model/export.py")

    val p       = MlpRefModel.Params.fromArtifacts(artDir)
    val goldens = readGoldenImages()
    goldens should not be empty

    progress("test: elaborating MlpEngine")
    test(new MlpEngine).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      progress("test: elaborated, entering test body")
      // chiseltest's default idle-cycle watchdog (1000) is well under the
      // ~2200 cycles one inference needs; our own maxCycles bound in
      // runInference is the real safety net, so just raise this out of the way.
      c.clock.setTimeout(0)
      loadWeights(c, p)
      loadBias(c, p)

      val cyclesByImage = scala.collection.mutable.Map.empty[Int, Int]
      for (g <- goldens) {
        val ref = MlpRefModel.infer(g.imageInt4, p)
        progress(s"test: image #${g.index}")
        loadImage(c, g.imageInt4)
        val cycles = runInference(c)
        cyclesByImage(g.index) = cycles
        progress(s"test: image #${g.index} took $cycles cycles")

        withClue(s"image #${g.index}: ") {
          for (i <- 0 until Layer2.n) {
            withClue(s"logit $i: ") { c.io.logits(i).peek().litValue shouldBe BigInt(ref.logits(i)) }
          }
          c.io.cls.peek().litValue.toInt shouldBe ref.pred
        }
      }
      progress(s"test: PASSED, ${goldens.length} images")
      progress(s"test: cycles per image = ${cyclesByImage.toSeq.sortBy(_._1)}")
      withClue(s"cycle counts should be identical across all images (tile counts are image-independent): " +
        s"${cyclesByImage.toSeq.sortBy(_._1)}") {
        cyclesByImage.values.toSet.size shouldBe 1
      }
    }
  }
}
