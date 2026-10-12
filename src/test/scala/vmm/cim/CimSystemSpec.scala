package vmm.cim

import java.nio.file.{Files, Path, Paths}

import chisel3._
import chiseltest._
import org.json4s._
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import vmm.mlp.MlpConfig._
import vmm.mlp.MlpRefModel
import CimMap._
import TcdmDriver._

/** End to end: the committed parameters and every committed golden image
  * go into the TCDM through the host port, [[MlpScheduler]] runs the
  * network on [[CimAccel]], and the logits and hidden activations left in
  * the TCDM plus the reported class must equal [[vmm.mlp.MlpRefModel]]
  * (itself bit-exact against `model/refmodel.py`) on every image.
  *
  * '''Needs the Verilator backend''' (the design is far too large for
  * treadle, and the TCDM banks are a Verilog black box). In this
  * environment Verilator's generated Makefile trips a GCC precompiled-header
  * bug (`V..__pch.h.slow: No such file or directory`, verilator/verilator
  * #4730 and friends), worked around by a `g++` shim on `PATH`
  * (`.toolshim/`, gitignored) plus `CCACHE_DISABLE=1`, which keeps ccache
  * from skipping the shim. The shim path must be absolute:
  * {{{
  * PATH=$PWD/.toolshim:$PATH CCACHE_DISABLE=1 sbt "testOnly vmm.cim.*"
  * }}}
  */
class CimSystemSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  private val artDir: Path = Paths.get("model", "artifacts")
  private def haveArtifacts: Boolean =
    Seq("layer1_w.hex", "layer2_w.hex", "layer1_b.hex", "layer2_b.hex", "golden.json")
      .forall(n => Files.exists(artDir.resolve(n)))

  private def readGoldenImages(): Seq[(Int, Array[Int])] = {
    implicit val fmt: Formats = DefaultFormats
    val text = new String(Files.readAllBytes(artDir.resolve("golden.json")), "UTF-8")
    val JArray(items) = parse(text)
    items.map(it => ((it \ "index").extract[Int], (it \ "image_int4").extract[Array[Int]]))
  }

  /** Weights and biases at their [[CimMap]] addresses. */
  private def loadParams(c: CimSystem, p: MlpRefModel.Params): Unit = {
    val rows = for {
      tiles <- Seq(p.w1Tiles, p.w2Tiles)
      o     <- tiles.indices
      k     <- tiles(o).indices
      row   <- 0 until Dim
    } yield tiles(o)(k)(row).toSeq
    rows.length shouldBe WeightRows
    writeWords(c.io.host, c.clock, WeightBase, rows.flatMap(pack))
    writeWords(c.io.host, c.clock, Bias1Base, p.b1Int.toSeq.map(u32))
    writeWords(c.io.host, c.clock, Bias2Base, p.b2Int.toSeq.map(u32).padTo(Layer2.nPadded, BigInt(0)))
  }

  private def runInference(c: CimSystem, maxCycles: Int = 50000): Int = {
    c.io.start.poke(true.B)
    c.clock.step(1)
    c.io.start.poke(false.B)
    var cycles = 0
    while (!c.io.done.peek().litToBoolean && cycles < maxCycles) { c.clock.step(1); cycles += 1 }
    c.io.done.expect(true.B, s"no done after $cycles cycles")
    c.io.error.expect(false.B)
    c.clock.step(1) // let the scheduler return to idle before the next start
    cycles
  }

  behavior of "CimSystem"

  it should "run every golden image from the TCDM and match MlpRefModel" in {
    assume(haveArtifacts, "model/artifacts not fully present -- run model/export.py")
    val p       = MlpRefModel.Params.fromArtifacts(artDir)
    val goldens = readGoldenImages()
    goldens should not be empty

    test(new CimSystem).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      c.clock.setTimeout(0)
      loadParams(c, p)

      val cycleCounts = goldens.map { case (index, image) =>
        val ref = MlpRefModel.infer(image, p)
        // The pad lanes of the last input-tile are written as zeros.
        writeWords(c.io.host, c.clock, ImgBase, pack(image.toSeq.padTo(Layer1.kPadded, 0)))
        val cycles = runInference(c)

        withClue(s"image #$index: ") {
          unpack(readWords(c.io.host, c.clock, HiddenBase, Layer1.n / LanesPerWord)) shouldBe ref.hiddenInt4.toSeq
          readWords(c.io.host, c.clock, LogitsBase, Layer2.n).map(s32) shouldBe ref.logits.toSeq
          c.io.cls.peek().litValue.toInt shouldBe ref.pred
        }
        cycles
      }
      info(s"${goldens.length} images, ${cycleCounts.head} cycles per inference")
      // the schedule does not depend on the data
      cycleCounts.distinct.length shouldBe 1
    }
  }
}
