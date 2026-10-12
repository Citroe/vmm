package vmm.cim

import vmm.mlp.MlpConfig._

/** Fixed layout shared by the TCDM, the accelerator and (for now) the MLP
  * scheduler: the TCDM geometry, where the image and the model parameters
  * sit in it, and the accelerator's register map. `fpga/gen_mlp_mem.py`
  * mirrors the memory map when it builds the bank images; later the RISC-V
  * firmware does too.
  *
  * All addresses are byte addresses; every access is one aligned 32-bit word.
  * INT4 vectors are packed 8 lanes to a word, lane 0 in the low nibble.
  *
  * {{{
  * 0x0000  image      800 INT4 (784 pixels + 16 zero pad)   100 words
  * 0x0200  hidden      64 INT4                                 8 words
  * 0x0240  logits      32 x signed 32b (10 real)              32 words
  * 0x0300  bias 1      64 x signed 32b                        64 words
  * 0x0400  bias 2      32 x signed 32b (10 real, rest 0)      32 words
  * 0x0800  weights   1664 rows x 4 words: layer 1's 50 tiles (output-tile
  *                   outer, input-tile inner), then layer 2's 2; within a
  *                   tile row 0 first                       6656 words
  * }}}
  */
object CimMap {
  // --- TCDM geometry: 4 banks x 8 kB (docs/riscv-cim-diagram.pdf) ---
  val Banks: Int     = 4
  val BankWords: Int = 2048
  val AddrWidth: Int = 15 // 32 kB, byte-addressed

  // --- vector packing ---
  val LanesPerWord: Int = 32 / InWidth      // 8
  val RowWords: Int     = Dim / LanesPerWord // 4 words: one weight row, or one activation vector
  val TileWords: Int    = Dim * RowWords     // 128
  val TileBytes: Int    = TileWords * 4      // 512
  require(Dim % LanesPerWord == 0, "a Dim-lane vector must fill whole words")

  /** Accumulator width in PostProc: the widest partial sum plus a bias of
    * the same width, plus one bit of headroom. */
  val AccumWidth: Int = math.max(Layer1.psumWidth, Layer2.psumWidth) + 2

  // --- memory map ---
  val ImgBase: Int    = 0x0000
  val HiddenBase: Int = 0x0200
  val LogitsBase: Int = 0x0240
  val Bias1Base: Int  = 0x0300
  val Bias2Base: Int  = 0x0400
  val WeightBase: Int = 0x0800
  val WeightRows: Int = (Layer1.passes + Layer2.passes) * Dim // 1664
  require(WeightBase + WeightRows * RowWords * 4 <= (1 << AddrWidth), "weights overflow the TCDM")

  /** Accelerator registers (byte offsets on the register port). */
  object Regs {
    val Ctrl: Int  = 0x00 // write bit 0: START.  read: Status bits
    val Mode: Int  = 0x04 // Mode bits, what the next job does
    val Shift: Int = 0x08 // requantisation right-shift for StoreAct
    val WPtr: Int  = 0x0c // weight tile, TileWords words
    val XPtr: Int  = 0x10 // activation vector, RowWords words
    val BPtr: Int  = 0x14 // bias, Dim words
    val YPtr: Int  = 0x18 // result: RowWords words (StoreAct) or Dim words (StoreRaw)
    val AddrWidth: Int = 5
  }

  /** `Regs.Mode` bits. A job runs the selected steps in this order. */
  object Mode {
    val LoadW: Int    = 1 << 0 // program the array from WPtr
    val AccClr: Int   = 1 << 1 // zero the accumulators
    val AccBias: Int  = 1 << 2 // preload the accumulators from BPtr
    val Compute: Int  = 1 << 3 // one pass with the vector at XPtr, accumulate y
    val StoreAct: Int = 1 << 4 // round-shift + clamp to INT4, store packed at YPtr
    val StoreRaw: Int = 1 << 5 // store the accumulators sign-extended at YPtr
    val Relu: Int     = 1 << 6 // with StoreAct: ReLU before the shift
    val Width: Int    = 7
  }

  /** `Regs.Ctrl` read bits. */
  object Status {
    val Busy: Int  = 1 << 0
    val Done: Int  = 1 << 1 // last job finished; cleared by START
    val Error: Int = 1 << 2 // last job aborted; cleared by START
  }
}
