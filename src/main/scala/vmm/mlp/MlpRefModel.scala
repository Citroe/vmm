package vmm.mlp

import java.nio.file.Path

import scala.io.Source

import MlpConfig._

/** Phase 2 -- a pure-Scala, integer, bit-exact model of the tiled quantised MLP.
  *
  * This is a line-for-line port of `model/refmodel.py` (the Python integer
  * reference). It exists to be cross-checked against that Python model on the
  * committed golden vectors (`model/artifacts/golden.json`) *before* any RTL is
  * written, and then reused as the golden model for the Phase 4 `MlpEngine`
  * chiseltest. No Chisel, no floats.
  *
  * Dataflow (identical to the RTL's intended shape and to `refmodel.py`):
  *   - 32-wide VMM passes: `y(col) = sum_row a(row) * W(row, col)`, exact INT13.
  *   - a partial-sum buffer sums `y` across input-tiles OUTSIDE the multiplier
  *     (the VMM does not re-accumulate across passes).
  *   - bias is added at the psum width (not INT4).
  *   - ReLU (layer 1 only), then an arithmetic-shift requant back to INT4.
  *   - argmax over layer 2's 10 real lanes -> predicted class.
  */
object MlpRefModel {

  // -------------------------------------------------------------------------
  // Quant primitives -- bit-exact mirror of model/quant.py
  // -------------------------------------------------------------------------

  /** Arithmetic-shift-right by `s` with round-half-up (`s >= 0`). Mirror of
    * `quant.round_shift` and the intended Verilog `(x + bias) >>> s`.
    * Scala `>>` on `Long` is an arithmetic (floor) shift, matching numpy int64. */
  def roundShift(x: Long, s: Int): Long = {
    require(s >= 0, s"roundShift needs s >= 0 (got $s); fixed-point exponents make the requant a right shift")
    if (s == 0) x else (x + (1L << (s - 1))) >> s
  }

  def clampInt4(x: Long): Int =
    math.max(Int4Min.toLong, math.min(Int4Max.toLong, x)).toInt

  /** layer psum (integer) -> next-layer INT4 activation code. */
  def requantToInt4(psum: Long, shift: Int): Int = clampInt4(roundShift(psum, shift))

  // -------------------------------------------------------------------------
  // The tiled dataflow -- bit-exact mirror of refmodel.tiled_layer
  // -------------------------------------------------------------------------

  /** One dense layer via `Dim x Dim` passes.
    *
    * @param actInt  length-`k` integer activation codes (INT4 for a real input)
    * @param wTiles  `wTiles(o)(k)(row)(col)` -- the stationary INT4 matrix for
    *                output-tile `o`, input-tile `k`, laid out so that
    *                `y(col) = sum_row a(row) * W(row, col)`
    * @param biasInt length-`n` integer bias at the accumulator fixed-point
    *                (`shape.psumWidth` bits, signed)
    * @param relu    true for layer 1: ReLU then requant to INT4 codes;
    *                false for layer 2: return the raw psum (logits path)
    * @return length-`n`: INT4 codes if `relu`, else the raw psum
    */
  def tiledLayer(actInt: Array[Int],
                 wTiles: Array[Array[Array[Array[Int]]]],
                 biasInt: Array[Long],
                 shape: LayerShape,
                 relu: Boolean,
                 requantShift: Int = Fxp.requantShift): Array[Long] = {
    require(actInt.length <= shape.kPadded, s"activation length ${actInt.length} > kPadded ${shape.kPadded}")

    // zero-pad the activation vector up to kPadded
    val a = new Array[Long](shape.kPadded)
    var i = 0
    while (i < actInt.length) { a(i) = actInt(i).toLong; i += 1 }

    val psum = new Array[Long](shape.nPadded) // the partial-sum buffer
    var o = 0
    while (o < shape.outputTiles) {
      val acc = new Array[Long](Dim)
      var kt = 0
      while (kt < shape.inputTiles) {
        val w = wTiles(o)(kt)
        var col = 0
        while (col < Dim) {
          var s = 0L
          var row = 0
          while (row < Dim) { s += a(kt * Dim + row) * w(row)(col); row += 1 }
          acc(col) += s // accumulate outside the VMM
          col += 1
        }
        kt += 1
      }
      System.arraycopy(acc, 0, psum, o * Dim, Dim)
      o += 1
    }

    val out = new Array[Long](shape.n)
    var j = 0
    while (j < shape.n) {
      var v = psum(j) + biasInt(j)
      if (relu) {
        if (v < 0L) v = 0L
        out(j) = requantToInt4(v, requantShift).toLong
      } else {
        out(j) = v
      }
      j += 1
    }
    out
  }

  final case class InferResult(hiddenInt4: Array[Int], logits: Array[Long], pred: Int)

  /** Full 784-64-10 inference. Mirror of `refmodel.infer`. */
  def infer(imageInt4: Array[Int], p: Params): InferResult = {
    val hidden = tiledLayer(imageInt4, p.w1Tiles, p.b1Int, Layer1, relu = true)
      .map(_.toInt)
    val logits = tiledLayer(hidden, p.w2Tiles, p.b2Int, Layer2, relu = false)
    InferResult(hidden, logits, argmax(logits))
  }

  /** First index of the maximum -- matches numpy's `argmax` tie-break. */
  def argmax(x: Array[Long]): Int = {
    var bi = 0
    var bv = x(0)
    var i = 1
    while (i < x.length) {
      if (x(i) > bv) { bv = x(i); bi = i }
      i += 1
    }
    bi
  }

  // -------------------------------------------------------------------------
  // Parameters, parsed straight from the committed .hex artefacts
  // -------------------------------------------------------------------------

  /** The integer weights/biases the model needs, loaded from the same `.hex`
    * files the Phase 3 `WMC` loader will consume -- so parsing them here also
    * exercises the loader contract in `model/export.py` / `manifest.json`. */
  final case class Params(w1Tiles: Array[Array[Array[Array[Int]]]],
                          w2Tiles: Array[Array[Array[Array[Int]]]],
                          b1Int: Array[Long],
                          b2Int: Array[Long])

  object Params {
    def fromArtifacts(dir: Path): Params = Params(
      readWeightHex(dir.resolve("layer1_w.hex"), Layer1),
      readWeightHex(dir.resolve("layer2_w.hex"), Layer2),
      readBiasHex(dir.resolve("layer1_b.hex"), Layer1),
      readBiasHex(dir.resolve("layer2_b.hex"), Layer2),
    )
  }

  private def hexLines(p: Path): Array[String] = {
    val src = Source.fromFile(p.toFile, "UTF-8")
    try src.getLines().map(_.trim).filter(_.nonEmpty).toArray
    finally src.close()
  }

  /** Parse a two's-complement INT4 nibble-per-line weight hex into
    * `wTiles(o)(k)(row)(col)`, using the loader contract from `manifest.json`:
    *   `line = ((o * input_tiles + k) * Dim + row) * Dim + col`
    * with the value a hex nibble (`-8..7 -> 0x8..0xF, 0x0..0x7`). */
  def readWeightHex(path: Path, shape: LayerShape): Array[Array[Array[Array[Int]]]] = {
    val nibs = hexLines(path).map { t =>
      val u = Integer.parseInt(t, 16)
      require(u >= 0 && u <= 0xF, s"$path: '$t' is not a hex nibble")
      if (u >= 8) u - 16 else u
    }
    val expect = shape.outputTiles * shape.inputTiles * Dim * Dim
    require(nibs.length == expect, s"$path: expected $expect nibbles, got ${nibs.length}")

    val w = Array.fill(shape.outputTiles, shape.inputTiles, Dim, Dim)(0)
    var idx = 0
    var o = 0
    while (o < shape.outputTiles) {
      var k = 0
      while (k < shape.inputTiles) {
        var row = 0
        while (row < Dim) {
          var col = 0
          while (col < Dim) { w(o)(k)(row)(col) = nibs(idx); idx += 1; col += 1 }
          row += 1
        }
        k += 1
      }
      o += 1
    }
    w
  }

  /** Parse the bias hex: one value per line, `shape.psumWidth`-bit two's
    * complement (written by `export.write_bias_hex` as `v & mask`, zero-padded). */
  def readBiasHex(path: Path, shape: LayerShape): Array[Long] = {
    val sign = 1L << (shape.psumWidth - 1)
    val span = 1L << shape.psumWidth
    val vals = hexLines(path).map { t =>
      val u = java.lang.Long.parseLong(t, 16)
      require(u >= 0 && u < span, s"$path: '$t' does not fit ${shape.psumWidth} bits")
      if ((u & sign) != 0L) u - span else u
    }
    require(vals.length == shape.n, s"$path: expected ${shape.n} biases, got ${vals.length}")
    vals
  }
}
