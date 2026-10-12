package vmm.cim

import chisel3._
import chisel3.experimental.{IntParam, StringParam}
import chisel3.util._

import CimMap._

/** One master's view of the TCDM, OBI-shaped: hold `req` with the address
  * phase signals until `gnt` is high in the same cycle; `rvalid` then
  * pulses the cycle after with `rdata` (for writes too, with `rdata`
  * meaningless). `req` must not depend combinationally on `gnt`.
  */
class TcdmPort extends Bundle {
  val req   = Output(Bool())
  val addr  = Output(UInt(AddrWidth.W)) // byte address, word-aligned access
  val we    = Output(Bool())
  val be    = Output(UInt(4.W))
  val wdata = Output(UInt(32.W))

  val gnt    = Input(Bool())
  val rvalid = Input(Bool())
  val rdata  = Input(UInt(32.W))
}

/** Black box for `src/main/resources/tcdm_bank.v` (Verilog so the byte
  * enables and the `$readmemh` initialisation infer one block RAM; a
  * `SyncReadMem` with a `Vec` mask is split per byte by the classic FIRRTL
  * compiler this project targets). */
class TcdmBank(initFile: String) extends BlackBox(Map(
  "INIT_FILE" -> StringParam(initFile),
  "AW"        -> IntParam(log2Ceil(BankWords)),
)) with HasBlackBoxResource {
  override def desiredName = "tcdm_bank"
  val io = IO(new Bundle {
    val clk   = Input(Clock())
    val en    = Input(Bool())
    val we    = Input(UInt(4.W))
    val addr  = Input(UInt(log2Ceil(BankWords).W))
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
  })
  addResource("/tcdm_bank.v")
}

/** Tightly coupled data memory: `Banks` single-port RAM banks behind a
  * single-cycle interconnect. The bank is the top address bits, so
  * masters addressing different banks are served in the same cycle; masters
  * contending for one bank are granted round-robin.
  *
  * @param nMasters  number of master ports
  * @param initFiles one `$readmemh` image per bank, or empty for all-zero
  *                  banks (simulation / tests that load through a port)
  */
class Tcdm(val nMasters: Int = 3, initFiles: Seq[String] = Nil) extends Module {
  require(initFiles.isEmpty || initFiles.length == Banks, s"need 0 or $Banks init files")

  val io = IO(new Bundle {
    val m = Vec(nMasters, Flipped(new TcdmPort))
  })

  private val wordW = log2Ceil(BankWords)
  private val bankW = log2Ceil(Banks)
  private val selW  = log2Ceil(nMasters) max 1
  require(wordW + 2 + bankW == AddrWidth)

  val bankOf = io.m.map(_.addr(AddrWidth - 1, AddrWidth - bankW))
  val rdatas = Wire(Vec(Banks, UInt(32.W)))
  val grants = Wire(Vec(Banks, Vec(nMasters, Bool())))

  for (b <- 0 until Banks) {
    val wants = VecInit(io.m.zip(bankOf).map { case (m, bank) => m.req && bank === b.U }).asUInt
    // Round-robin: the first requester after the last one served, else the
    // first requester at all.
    val last  = RegInit((nMasters - 1).U(selW.W))
    val after = wants & VecInit((0 until nMasters).map(_.U > last)).asUInt
    val sel   = Mux(after.orR, PriorityEncoder(after), PriorityEncoder(wants))
    val any   = wants.orR
    when(any) { last := sel }
    for (i <- 0 until nMasters) grants(b)(i) := any && sel === i.U

    val bank = Module(new TcdmBank(if (initFiles.isEmpty) "" else initFiles(b)))
    val m    = io.m(sel)
    bank.io.clk   := clock
    bank.io.en    := any
    bank.io.we    := Mux(m.we, m.be, 0.U)
    bank.io.addr  := m.addr(wordW + 1, 2)
    bank.io.wdata := m.wdata
    rdatas(b)     := bank.io.rdata
  }

  for (i <- 0 until nMasters) {
    val gnt = VecInit(grants.map(_(i))).asUInt.orR
    io.m(i).gnt    := gnt
    io.m(i).rvalid := RegNext(gnt, false.B)
    io.m(i).rdata  := rdatas(RegEnable(bankOf(i), gnt))
  }
}
