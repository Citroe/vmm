package vmm.cim

import chisel3._
import chisel3.util._

import CimMap._

/** Moves a burst of consecutive words between the TCDM and its user, one
  * word per granted cycle.
  *
  * Pulse `start` (while not `busy`) with `ptr`, `count` (>= 1) and `write`.
  *   - read: each word comes back as an `rvalid` pulse with `rdata`, in
  *     address order.
  *   - write: `wdata` must hold the next word to store; `wfire` pulses on
  *     the cycle it is taken, so the user advances to the following word.
  * `done` pulses the cycle after the last word's response, with `busy`
  * already low.
  */
class Streamer(maxWords: Int) extends Module {
  private val cw = log2Ceil(maxWords + 1)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val write = Input(Bool())
    val ptr   = Input(UInt(AddrWidth.W))
    val count = Input(UInt(cw.W))

    val busy = Output(Bool())
    val done = Output(Bool())

    val rvalid = Output(Bool())
    val rdata  = Output(UInt(32.W))

    val wdata = Input(UInt(32.W))
    val wfire = Output(Bool())

    val tcdm = new TcdmPort
  })

  val busy  = RegInit(false.B)
  val done  = RegInit(false.B)
  val isWr  = Reg(Bool())
  val addr  = Reg(UInt(AddrWidth.W))
  val toReq = Reg(UInt(cw.W)) // words still to request
  val toRsp = Reg(UInt(cw.W)) // responses still outstanding

  io.tcdm.req   := busy && toReq =/= 0.U
  io.tcdm.addr  := addr
  io.tcdm.we    := isWr
  io.tcdm.be    := "b1111".U
  io.tcdm.wdata := io.wdata

  val fire = io.tcdm.req && io.tcdm.gnt
  val rsp  = busy && io.tcdm.rvalid

  done := false.B
  when(fire) {
    addr  := addr + 4.U
    toReq := toReq - 1.U
  }
  when(rsp) {
    toRsp := toRsp - 1.U
    when(toRsp === 1.U) {
      busy := false.B
      done := true.B
    }
  }
  when(io.start && !busy) {
    busy  := true.B
    isWr  := io.write
    addr  := io.ptr
    toReq := io.count
    toRsp := io.count
  }

  io.busy   := busy
  io.done   := done
  io.rvalid := rsp && !isWr
  io.rdata  := io.tcdm.rdata
  io.wfire  := fire && isWr
}
