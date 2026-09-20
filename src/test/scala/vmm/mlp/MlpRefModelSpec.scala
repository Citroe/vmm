package vmm.mlp

import java.nio.file.{Files, Path, Paths}

import org.json4s._
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private final case class Golden(index: Int, label: Int,
                                imageInt4: Array[Int], hiddenInt4: Array[Int],
                                logitsInt: Array[Long], pred: Int)

/** Phase 2 gate: the pure-Scala [[MlpRefModel]] must reproduce
  * `model/refmodel.py`'s integer intermediates bit-for-bit on every committed
  * golden image, before any RTL is written against it.
  *
  * sbt runs from the repo root, so `model/artifacts` resolves relative to CWD.
  * The tests `assume` the artefacts exist (regenerate with
  * `python model/export.py --ckpt model/checkpoints/qat.pt`) rather than fail
  * when the offline flow hasn't been run.
  */
class MlpRefModelSpec extends AnyFlatSpec with Matchers {

  private val artDir: Path = Paths.get("model", "artifacts")
  private def have(name: String): Boolean = Files.exists(artDir.resolve(name))
  private def text(name: String): String =
    new String(Files.readAllBytes(artDir.resolve(name)), "UTF-8")

  private def readGolden(): Seq[Golden] = {
    implicit val fmt: Formats = DefaultFormats
    val JArray(items) = parse(text("golden.json"))
    items.map { it =>
      Golden(
        index      = (it \ "index").extract[Int],
        label      = (it \ "label").extract[Int],
        imageInt4  = (it \ "image_int4").extract[Array[Int]],
        hiddenInt4 = (it \ "hidden_int4").extract[Array[Int]],
        logitsInt  = (it \ "logits_int").extract[Array[Long]],
        pred       = (it \ "pred").extract[Int],
      )
    }
  }

  behavior of "MlpRefModel (Phase 2 Scala bit-exact model)"

  it should "load the committed weight/bias hex with the expected tile shapes" in {
    assume(have("layer1_w.hex") && have("layer2_w.hex") &&
           have("layer1_b.hex") && have("layer2_b.hex"),
           "model/artifacts/*.hex not present -- run model/export.py")

    val p = MlpRefModel.Params.fromArtifacts(artDir)

    p.w1Tiles.length shouldBe MlpConfig.Layer1.outputTiles
    p.w1Tiles(0).length shouldBe MlpConfig.Layer1.inputTiles
    p.w1Tiles(0)(0).length shouldBe MlpConfig.Dim
    p.w1Tiles(0)(0)(0).length shouldBe MlpConfig.Dim
    p.w2Tiles.length shouldBe MlpConfig.Layer2.outputTiles
    p.w2Tiles(0).length shouldBe MlpConfig.Layer2.inputTiles
    p.b1Int.length shouldBe MlpConfig.Layer1.n
    p.b2Int.length shouldBe MlpConfig.Layer2.n

    // every weight is a legal INT4 code
    for (o <- p.w1Tiles.indices; k <- p.w1Tiles(o).indices;
         row <- 0 until MlpConfig.Dim; col <- 0 until MlpConfig.Dim) {
      p.w1Tiles(o)(k)(row)(col) should (be >= MlpConfig.Int4Min and be <= MlpConfig.Int4Max)
    }
  }

  it should "agree with scales.json: MlpConfig.Fxp matches the frozen export" in {
    assume(have("scales.json"), "model/artifacts/scales.json not present")
    implicit val fmt: Formats = DefaultFormats
    val j = parse(text("scales.json"))

    (j \ "e_act_in").extract[Int] shouldBe MlpConfig.Fxp.eActIn
    (j \ "e_w1").extract[Int] shouldBe MlpConfig.Fxp.eW1
    (j \ "e_act_hidden").extract[Int] shouldBe MlpConfig.Fxp.eActHidden
    (j \ "e_w2").extract[Int] shouldBe MlpConfig.Fxp.eW2
    (j \ "requant_shift").extract[Int] shouldBe MlpConfig.Fxp.requantShift
    (j \ "acc_exp_layer1").extract[Int] shouldBe MlpConfig.Fxp.accExp(0)
    (j \ "acc_exp_layer2").extract[Int] shouldBe MlpConfig.Fxp.accExp(1)
    MlpConfig.Fxp.requantShift should be >= 0
  }

  it should "match model/refmodel.py bit-for-bit on every golden image (hidden, logits, pred)" in {
    assume(have("golden.json") && have("layer1_w.hex") && have("scales.json"),
           "model/artifacts not fully present -- run model/export.py")

    val p = MlpRefModel.Params.fromArtifacts(artDir)
    val golden = readGolden()
    golden should not be empty

    for (g <- golden) {
      val r = MlpRefModel.infer(g.imageInt4, p)
      withClue(s"image #${g.index} (label ${g.label}) hidden_int4: ") {
        r.hiddenInt4.toSeq shouldBe g.hiddenInt4.toSeq
      }
      withClue(s"image #${g.index} (label ${g.label}) logits_int: ") {
        r.logits.toSeq shouldBe g.logitsInt.toSeq
      }
      withClue(s"image #${g.index} (label ${g.label}) pred: ") {
        r.pred shouldBe g.pred
      }
    }
  }

  it should "round_shift with round-half-up, arithmetic (floor) shift for negatives" in {
    // spot values against the Python semantics of (x + (1<<(s-1))) >> s
    MlpRefModel.roundShift(0L, 0) shouldBe 0L
    MlpRefModel.roundShift(7L, 1) shouldBe 4L      // (7+1)>>1
    MlpRefModel.roundShift(-3L, 1) shouldBe -1L    // (-3+1)>>1 = -2>>1
    MlpRefModel.roundShift(-5L, 2) shouldBe -1L    // (-5+2)>>2 = -3>>2 = -1 (floor)
    MlpRefModel.roundShift(255L, 7) shouldBe 2L    // (255+64)>>7
    MlpRefModel.requantToInt4(10000L, 7) shouldBe MlpConfig.Int4Max
    MlpRefModel.requantToInt4(-10000L, 7) shouldBe MlpConfig.Int4Min
  }
}
