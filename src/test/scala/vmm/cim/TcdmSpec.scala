package vmm.cim

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import TcdmDriver._

/** [[Tcdm]] on its own: storage, byte enables, and the interconnect's two
  * cases -- masters on different banks served together, masters on one bank
  * served in turn. Needs the Verilator backend (the banks are a Verilog
  * black box); run as described in `CimSystemSpec`.
  */
class TcdmSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val annos = Seq(VerilatorBackendAnnotation)

  behavior of "Tcdm"

  it should "store and return words in every bank, honouring byte enables" in {
    test(new Tcdm(nMasters = 2)).withAnnotations(annos) { c =>
      val m = c.io.m(0)
      // first and last word of each 8 kB bank
      val addrs = (0 until CimMap.Banks).flatMap(b => Seq(b * 0x2000, b * 0x2000 + 0x1ffc))
      addrs.zipWithIndex.foreach { case (a, i) => write(m, c.clock, a, BigInt(0x11110000L + i)) }
      addrs.zipWithIndex.foreach { case (a, i) => read(m, c.clock, a) shouldBe BigInt(0x11110000L + i) }

      write(m, c.clock, 0x0100, BigInt("aabbccdd", 16))
      write(m, c.clock, 0x0100, BigInt("11223344", 16), be = 0x5) // bytes 0 and 2 only
      read(m, c.clock, 0x0100) shouldBe BigInt("aa22cc44", 16)
      // unwritten memory reads as zero
      read(m, c.clock, 0x0104) shouldBe BigInt(0)
    }
  }

  it should "grant two masters in the same cycle when they address different banks" in {
    test(new Tcdm(nMasters = 2)).withAnnotations(annos) { c =>
      val Seq(a, b) = c.io.m.toSeq
      for ((m, addr, data) <- Seq((a, 0x0010, 0x1234), (b, 0x2010, 0x5678))) {
        m.req.poke(true.B); m.addr.poke(addr.U); m.we.poke(true.B); m.be.poke(0xf.U); m.wdata.poke(data.U)
      }
      a.gnt.expect(true.B)
      b.gnt.expect(true.B)
      c.clock.step(1)
      a.req.poke(false.B); b.req.poke(false.B)
      a.rvalid.expect(true.B)
      b.rvalid.expect(true.B)
      read(a, c.clock, 0x2010) shouldBe BigInt(0x5678)
      read(b, c.clock, 0x0010) shouldBe BigInt(0x1234)
    }
  }

  it should "serve two masters contending for one bank in turn, alternating" in {
    test(new Tcdm(nMasters = 2)).withAnnotations(annos) { c =>
      val ms = c.io.m.toSeq
      write(ms(0), c.clock, 0x0020, BigInt(0xa0))
      write(ms(0), c.clock, 0x0024, BigInt(0xa1))

      // Both read the same bank, holding req until served; each keeps its
      // own address and must get its own data back.
      ms(0).req.poke(true.B); ms(0).addr.poke(0x0020.U); ms(0).we.poke(false.B)
      ms(1).req.poke(true.B); ms(1).addr.poke(0x0024.U); ms(1).we.poke(false.B)
      var order   = Seq.empty[Int]
      val got     = Array.fill(2)(BigInt(-1))
      var pending = Option.empty[Int]
      for (_ <- 0 until 4) {
        val grants = ms.map(_.gnt.peek().litToBoolean)
        grants.count(identity) should be <= 1
        pending.foreach { i =>
          ms(i).rvalid.expect(true.B)
          got(i) = ms(i).rdata.peek().litValue
        }
        val granted = grants.indexOf(true)
        pending = if (granted >= 0) Some(granted) else None
        c.clock.step(1)
        if (granted >= 0) {
          order :+= granted
          if (order.count(_ == granted) == 2) ms(granted).req.poke(false.B)
        }
      }
      // two requests each, strictly alternating
      order.length shouldBe 4
      order.sliding(2).foreach { case Seq(x, y) => x should not be y }
      got(0) shouldBe BigInt(0xa0)
      got(1) shouldBe BigInt(0xa1)
    }
  }
}
