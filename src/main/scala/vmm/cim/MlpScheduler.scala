package vmm.cim

import chisel3._
import chisel3.util._

import vmm.mlp.MlpConfig._
import CimMap._

/** TEMPORARY stand-in for the RISC-V host: runs the 784-64-10 MLP by
  * issuing [[CimAccel]] jobs over its register port, exactly as firmware
  * will, then reads the logits back from the TCDM and takes the argmax.
  * Delete this (and hand the register port and a TCDM master port to the
  * core) once the processor is in.
  *
  * One job per 32x32 tile, in the order the tiles are stored:
  *   - layer 1, output-tile `o` = 0, 1: input-tiles `k` = 0..24 over the
  *     image; bias preload on `k` = 0, ReLU + requant into the hidden
  *     vector on `k` = 24.
  *   - layer 2: `k` = 0, 1 over the hidden vector; bias preload on `k` = 0,
  *     raw store of the logits on `k` = 1.
  * 52 jobs in all, each loading its own weight tile.
  *
  * Pulse `start` (rising edge) with the image and parameters in the TCDM;
  * `done` pulses with `cls` valid, which then holds. `error` is sticky until
  * the next `start`.
  */
class MlpScheduler extends Module {
  private val clsW = log2Ceil(Layer2.n)
  private val kW   = log2Ceil(Layer1.inputTiles)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val busy  = Output(Bool())
    val done  = Output(Bool())
    val error = Output(Bool())
    val cls   = Output(UInt(clsW.W))

    val reg      = new CimRegPort
    val accDone  = Input(Bool())
    val accError = Input(Bool())
    val tcdm     = new TcdmPort
  })

  val st = Module(new Streamer(Layer2.n))
  io.tcdm <> st.io.tcdm

  val sIdle :: sShift :: sMode :: sWPtr :: sXPtr :: sBPtr :: sYPtr :: sGo :: sWait :: sArgStart :: sArg :: sDone :: Nil = Enum(12)
  val state = RegInit(sIdle)

  val layer2 = RegInit(false.B)
  val oReg   = RegInit(0.U(1.W))
  val kReg   = RegInit(0.U(kW.W))
  val wPtr   = RegInit(WeightBase.U(AddrWidth.W))
  val errReg = RegInit(false.B)

  val clsReg  = RegInit(0.U(clsW.W))
  val bestReg = Reg(SInt(32.W))
  val argIdx  = Reg(UInt(clsW.W))

  val startPulse = io.start && !RegNext(io.start, false.B)

  val firstK = kReg === 0.U
  val lastK  = kReg === Mux(layer2, (Layer2.inputTiles - 1).U, (Layer1.inputTiles - 1).U)

  val jobMode = (Mode.LoadW | Mode.Compute).U |
    Mux(firstK, Mode.AccBias.U, 0.U) |
    Mux(lastK, Mux(layer2, Mode.StoreRaw.U, (Mode.StoreAct | Mode.Relu).U), 0.U)
  // One input-tile is RowWords words, one output-tile's bias Dim words.
  val kOff = (kReg << log2Ceil(RowWords * 4)).asUInt
  val xPtr = Mux(layer2, HiddenBase.U, ImgBase.U) + kOff
  val bPtr = Mux(layer2, Bias2Base.U, Mux(oReg === 1.U, (Bias1Base + Dim * 4).U, Bias1Base.U))
  val yPtr = Mux(layer2, LogitsBase.U, Mux(oReg === 1.U, (HiddenBase + RowWords * 4).U, HiddenBase.U))

  io.reg.wen   := false.B
  io.reg.addr  := 0.U
  io.reg.wdata := 0.U
  private def write(reg: Int, data: UInt): Unit = {
    io.reg.wen   := true.B
    io.reg.addr  := reg.U
    io.reg.wdata := data
  }

  st.io.start := false.B
  st.io.write := false.B
  st.io.ptr   := LogitsBase.U
  st.io.count := Layer2.n.U
  st.io.wdata := 0.U

  io.busy  := state =/= sIdle
  io.done  := false.B
  io.error := errReg
  io.cls   := clsReg

  switch(state) {
    is(sIdle) {
      when(startPulse) {
        layer2 := false.B
        oReg   := 0.U
        kReg   := 0.U
        wPtr   := WeightBase.U
        errReg := false.B
        state  := sShift
      }
    }

    is(sShift) { write(Regs.Shift, Fxp.requantShift.U); state := sMode }
    is(sMode)  { write(Regs.Mode, jobMode); state := sWPtr }
    is(sWPtr)  { write(Regs.WPtr, wPtr); state := sXPtr }
    is(sXPtr)  { write(Regs.XPtr, xPtr); state := sBPtr }
    is(sBPtr)  { write(Regs.BPtr, bPtr); state := sYPtr }
    is(sYPtr)  { write(Regs.YPtr, yPtr); state := sGo }
    is(sGo)    { write(Regs.Ctrl, 1.U); state := sWait }

    is(sWait) {
      when(io.accDone) {
        wPtr := wPtr + TileBytes.U
        when(io.accError) {
          errReg := true.B
          state  := sIdle
        }.elsewhen(!lastK) {
          kReg  := kReg + 1.U
          state := sMode
        }.elsewhen(layer2) {
          state := sArgStart
        }.otherwise {
          kReg := 0.U
          when(oReg === (Layer1.outputTiles - 1).U) {
            oReg   := 0.U
            layer2 := true.B
          }.otherwise { oReg := oReg + 1.U }
          state := sMode
        }
      }
    }

    is(sArgStart) {
      st.io.start := true.B
      argIdx      := 0.U
      state       := sArg
    }

    is(sArg) {
      // One logit per cycle against the running maximum; strict `>` keeps
      // the lowest index on ties, matching the reference argmax.
      val cur = st.io.rdata.asSInt
      when(st.io.rvalid) {
        when(argIdx === 0.U || cur > bestReg) {
          bestReg := cur
          clsReg  := argIdx
        }
        argIdx := argIdx + 1.U
      }
      when(st.io.done) { state := sDone }
    }

    is(sDone) {
      io.done := true.B
      state   := sIdle
    }
  }
}
