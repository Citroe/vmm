package vmm.mlp

/** Pure-Scala mirror of `model/config.py` -- the fixed hardware contract the
  * offline flow (Phase 1) and the RTL (Phase 3+) both target. No Chisel here;
  * this is shared by the Phase 2 reference model and, later, `MlpEngine`.
  *
  * Every constant here must reconcile against `model/config.py` and
  * `src/main/scala/vmm/VmmParams.scala` (`VmmParams(dim = 32, inWidth = 4)`).
  */
object MlpConfig {

  // --- VMM core geometry: must match VmmParams(dim = 32, inWidth = 4) ---
  val Dim: Int       = 32
  val InWidth: Int   = 4
  val ProdWidth: Int = 2 * InWidth                 // 8
  val AccWidth: Int  = ProdWidth + bitLength(Dim - 1) // 8 + 5 = 13

  val Int4Min: Int = -(1 << (InWidth - 1))         // -8
  val Int4Max: Int = (1 << (InWidth - 1)) - 1      //  7

  /** Python `int.bit_length()` for x >= 0: 0 -> 0, 1 -> 1, 24 -> 5, 31 -> 5. */
  def bitLength(x: Int): Int = {
    require(x >= 0, s"bitLength expects x >= 0, got $x")
    if (x == 0) 0 else 32 - Integer.numberOfLeadingZeros(x)
  }

  /** One dense layer's tiling, matching `config.LayerShape` / PLAN.md's table. */
  final case class LayerShape(name: String, k: Int, n: Int, relu: Boolean) {
    val inputTiles: Int  = (k + Dim - 1) / Dim
    val outputTiles: Int = (n + Dim - 1) / Dim
    val kPadded: Int     = inputTiles * Dim
    val nPadded: Int     = outputTiles * Dim
    val passes: Int      = inputTiles * outputTiles

    /** accWidth + ceil(log2(input_tiles)) -- wide enough for the cross-input-tile
      * sum held in the partial-sum buffer outside the VMM. */
    val psumWidth: Int = AccWidth + math.max(0, bitLength(inputTiles - 1))
  }

  val Layer1: LayerShape = LayerShape("layer1", k = 784, n = 64, relu = true)
  val Layer2: LayerShape = LayerShape("layer2", k = 64, n = 10, relu = false)
  val Layers: Seq[LayerShape] = Seq(Layer1, Layer2)

  // --- fixed-point convention ("FP -> fxd. F", PLAN.md open decision #3) ---
  //
  // Per-tensor power-of-two scales: an INT code `q` represents `q * 2^e`, one
  // integer `e` per tensor. These mirror `model/config.py`'s `FixedPointConfig`
  // and are frozen in `model/artifacts/scales.json` (the authoritative export;
  // `MlpRefModelSpec` asserts these values match that file). Only `requantShift`
  // feeds the integer arithmetic -- the exponents themselves are bookkeeping for
  // interpreting the result.
  object Fxp {
    val eActIn: Int     = -1  // normalised pixels
    val eW1: Int        = -5  // layer-1 weights
    val eActHidden: Int =  1  // layer-1 ReLU output
    val eW2: Int        = -3  // layer-2 weights

    def accExp(layerIdx: Int): Int = layerIdx match {
      case 0 => eW1 + eActIn
      case 1 => eW2 + eActHidden
      case _ => throw new IndexOutOfBoundsException(layerIdx.toString)
    }

    /** layer-1 psum -> hidden INT4 activation. Must be >= 0 (right shift). */
    val requantShift: Int = eActHidden - accExp(0)
    require(requantShift >= 0, s"requantShift = $requantShift < 0")
  }

  // Sanity vs PLAN.md's layer-mapping table / config.py's asserts.
  require(Layer1.inputTiles == 25 && Layer1.outputTiles == 2 && Layer1.passes == 50,
    s"layer1 tiling wrong: $Layer1")
  require(Layer2.inputTiles == 2 && Layer2.outputTiles == 1 && Layer2.passes == 2,
    s"layer2 tiling wrong: $Layer2")
  require(Layer1.psumWidth == 18 && Layer2.psumWidth == 14,
    s"psum widths wrong: ${Layer1.psumWidth}, ${Layer2.psumWidth}")
  require(AccWidth == 13, s"accWidth = $AccWidth, expected 13")
}
