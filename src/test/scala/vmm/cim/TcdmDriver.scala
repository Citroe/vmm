package vmm.cim

import chisel3._
import chiseltest._

/** Test-side driver for one [[TcdmPort]] master and for the accelerator's
  * register port, plus the INT4 word packing of [[CimMap]]. */
object TcdmDriver {
  private val Mask32 = (BigInt(1) << 32) - 1

  /** Two's-complement 32-bit pattern of a signed value. */
  def u32(v: Long): BigInt = BigInt(v) & Mask32

  /** Signed value of a 32-bit pattern. */
  def s32(v: BigInt): Long = v.toInt.toLong

  /** INT4 lanes -> words, 8 lanes per word, lane 0 in the low nibble. */
  def pack(lanes: Seq[Int]): Seq[BigInt] = {
    require(lanes.length % 8 == 0)
    lanes.grouped(8).map(_.zipWithIndex.map { case (v, i) => BigInt(v & 0xf) << (4 * i) }.sum).toSeq
  }

  /** Inverse of [[pack]], sign-extending each nibble. */
  def unpack(words: Seq[BigInt]): Seq[Int] =
    words.flatMap(w => (0 until 8).map { i =>
      val n = ((w >> (4 * i)) & 0xf).toInt
      if (n >= 8) n - 16 else n
    })

  def write(port: TcdmPort, clock: Clock, addr: Int, data: BigInt, be: Int = 0xf): Unit = {
    port.req.poke(true.B)
    port.addr.poke(addr.U)
    port.we.poke(true.B)
    port.be.poke(be.U)
    port.wdata.poke(data.U)
    while (!port.gnt.peek().litToBoolean) clock.step(1)
    clock.step(1)
    port.req.poke(false.B)
    port.we.poke(false.B)
  }

  def read(port: TcdmPort, clock: Clock, addr: Int): BigInt = {
    port.req.poke(true.B)
    port.addr.poke(addr.U)
    port.we.poke(false.B)
    while (!port.gnt.peek().litToBoolean) clock.step(1)
    clock.step(1)
    port.req.poke(false.B)
    port.rvalid.expect(true.B)
    port.rdata.peek().litValue
  }

  def writeWords(port: TcdmPort, clock: Clock, base: Int, words: Seq[BigInt]): Unit =
    words.zipWithIndex.foreach { case (w, i) => write(port, clock, base + 4 * i, w) }

  def readWords(port: TcdmPort, clock: Clock, base: Int, n: Int): Seq[BigInt] =
    (0 until n).map(i => read(port, clock, base + 4 * i))

  def regWrite(port: CimRegPort, clock: Clock, addr: Int, data: BigInt): Unit = {
    port.wen.poke(true.B)
    port.addr.poke(addr.U)
    port.wdata.poke(data.U)
    clock.step(1)
    port.wen.poke(false.B)
  }
}
