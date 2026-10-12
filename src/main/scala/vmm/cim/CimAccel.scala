package vmm.cim

import chisel3._
import chisel3.util._

import vmm.{VmmIO, VmmParams, VmmSequencer}
import vmm.mlp.MlpConfig._
import CimMap._

/** The accelerator's register port: a write strobe with address and data,
  * and a combinational read of the addressed register. An APB (or any
  * other bus) front end is a thin wrapper over this. */
class CimRegPort extends Bundle {
  val wen   = Output(Bool())
  val addr  = Output(UInt(Regs.AddrWidth.W))
  val wdata = Output(UInt(32.W))
  val rdata = Input(UInt(32.W))
}

/** The digital wrapper around the array (the "CIM accelerator" box of
  * docs/riscv-cim-diagram.pdf, minus the array itself, which is reached
  * through `core`): control/status registers, a [[Streamer]] between the
  * TCDM and the array, the array's [[vmm.VmmSequencer]], and [[PostProc]].
  *
  * It knows nothing about the network being run. Whoever owns the register
  * port -- [[MlpScheduler]] today, RISC-V firmware later -- sets the
  * pointers and `Mode` (see [[CimMap]]), writes START, and waits for `done`.
  * One job runs the selected steps in this fixed order:
  *
  *   1. `LoadW`: read a `Dim x Dim` INT4 tile at `WPtr` (4 words per row)
  *      and program it into the array.
  *   1. `AccClr` / `AccBias`: zero the accumulators, or preload them with
  *      the `Dim` words at `BPtr`.
  *   1. `Compute`: read the `Dim`-lane INT4 vector at `XPtr`, run one pass,
  *      add the array's `y` into the accumulators.
  *   1. `StoreAct` (`Dim` INT4 activations, packed, optional `Relu`, right
  *      shift `Shift`) or `StoreRaw` (`Dim` sign-extended words) to `YPtr`.
  *
  * A layer wider than the array is therefore a run of jobs: `AccBias` on
  * the first input-tile, plain `Compute` jobs in between, a store on the
  * last. The weights stay resident between jobs, so `LoadW` is only needed
  * when the tile changes.
  *
  * `done` pulses for one cycle when a job ends, including when it aborts
  * (the sequencer reported an error); `error` and the `Status` bits hold
  * until the next START. Registers must not be written while `busy`.
  */
class CimAccel(
  val loadTimeout: Int = 1024,
  val computeTimeout: Int = 0,
) extends Module {
  private val p = VmmParams(dim = Dim, inWidth = InWidth)

  val io = IO(new Bundle {
    val reg   = Flipped(new CimRegPort)
    val busy  = Output(Bool())
    val done  = Output(Bool())
    val error = Output(Bool())
    val tcdm  = new TcdmPort
    val core  = Flipped(new VmmIO(p))
  })

  val seq  = Module(new VmmSequencer(p, loadTimeout, computeTimeout))
  val st   = Module(new Streamer(TileWords))
  val post = Module(new PostProc)
  io.core <> seq.core
  io.tcdm <> st.io.tcdm

  // ---------------------------------------------------------------------
  // Control / status registers
  // ---------------------------------------------------------------------
  val mode  = RegInit(0.U(Mode.Width.W))
  val shift = RegInit(0.U(5.W))
  val wPtr  = RegInit(0.U(AddrWidth.W))
  val xPtr  = RegInit(0.U(AddrWidth.W))
  val bPtr  = RegInit(0.U(AddrWidth.W))
  val yPtr  = RegInit(0.U(AddrWidth.W))

  val doneReg = RegInit(false.B)
  val errReg  = RegInit(false.B)

  private def bit(m: Int): Bool = mode(log2Ceil(m))

  val sIdle :: sLoadW :: sLoadWTail :: sBias :: sLoadX :: sCompStart :: sCompWait :: sPack :: sStoreAct :: sStoreRaw :: sFinish :: Nil = Enum(11)
  val state = RegInit(sIdle)

  when(io.reg.wen) {
    switch(io.reg.addr) {
      is(Regs.Mode.U)  { mode := io.reg.wdata }
      is(Regs.Shift.U) { shift := io.reg.wdata }
      is(Regs.WPtr.U)  { wPtr := io.reg.wdata }
      is(Regs.XPtr.U)  { xPtr := io.reg.wdata }
      is(Regs.BPtr.U)  { bPtr := io.reg.wdata }
      is(Regs.YPtr.U)  { yPtr := io.reg.wdata }
    }
  }
  val status = Cat(errReg, doneReg, state =/= sIdle)
  io.reg.rdata := MuxLookup(io.reg.addr, 0.U)(Seq(
    Regs.Ctrl.U  -> status,
    Regs.Mode.U  -> mode,
    Regs.Shift.U -> shift,
    Regs.WPtr.U  -> wPtr,
    Regs.XPtr.U  -> xPtr,
    Regs.BPtr.U  -> bPtr,
    Regs.YPtr.U  -> yPtr,
  ))
  val startJob = io.reg.wen && io.reg.addr === Regs.Ctrl.U && io.reg.wdata(0) && state === sIdle

  // ---------------------------------------------------------------------
  // Datapath between the streamer, the array and the accumulators
  // ---------------------------------------------------------------------
  // Weight row being assembled from RowWords consecutive words (lane 0 in
  // the low nibble of the first), handed to the sequencer once complete.
  // Rows complete at most every RowWords cycles and the sequencer takes one
  // per cycle, so a finished row never waits behind the previous one.
  val rowReg   = Reg(UInt((Dim * InWidth).W))
  val rowWord  = Reg(UInt(log2Ceil(RowWords).W))
  val rowValid = RegInit(false.B)
  // Activation vector, same packing, and the packed activations on the way out.
  val xReg    = Reg(UInt((Dim * InWidth).W))
  val actReg  = Reg(UInt((Dim * InWidth).W))
  val packCtr = Reg(UInt(log2Ceil(Dim + 1).W))

  private def lanes(v: UInt): Vec[SInt] =
    VecInit(Seq.tabulate(Dim)(i => v((i + 1) * InWidth - 1, i * InWidth).asSInt))

  seq.io.loadStart       := false.B
  seq.io.weightRow.valid := rowValid
  seq.io.weightRow.bits  := lanes(rowReg)
  seq.io.computeStart    := false.B
  seq.io.a               := lanes(xReg)
  when(seq.io.weightRow.fire) { rowValid := false.B }

  st.io.start := false.B
  st.io.write := false.B
  st.io.ptr   := wPtr
  st.io.count := TileWords.U
  st.io.wdata := actReg(31, 0)

  post.io.clear    := false.B
  post.io.add      := false.B
  post.io.y        := seq.io.y
  post.io.load     := false.B
  post.io.loadData := st.io.rdata(AccumWidth - 1, 0).asSInt
  post.io.rotate   := false.B
  post.io.relu     := bit(Mode.Relu)
  post.io.shift    := shift

  io.busy  := state =/= sIdle
  io.done  := false.B
  io.error := errReg

  private def burst(ptr: UInt, words: Int, write: Boolean): Unit = {
    st.io.start := true.B
    st.io.write := write.B
    st.io.ptr   := ptr
    st.io.count := words.U
  }

  // Enter the first selected step at or after `step`.
  private def enter(step: Int): Unit = step match {
    case 0 =>
      when(bit(Mode.LoadW)) {
        seq.io.loadStart := true.B
        burst(wPtr, TileWords, write = false)
        rowWord := 0.U
        state   := sLoadW
      }.otherwise { enter(1) }
    case 1 =>
      when(bit(Mode.AccBias)) {
        burst(bPtr, Dim, write = false)
        state := sBias
      }.otherwise {
        when(bit(Mode.AccClr)) { post.io.clear := true.B }
        enter(2)
      }
    case 2 =>
      when(bit(Mode.Compute)) {
        burst(xPtr, RowWords, write = false)
        state := sLoadX
      }.otherwise { enter(3) }
    case 3 =>
      when(bit(Mode.StoreAct)) {
        packCtr := 0.U
        state   := sPack
      }.elsewhen(bit(Mode.StoreRaw)) {
        burst(yPtr, Dim, write = true)
        state := sStoreRaw
      }.otherwise { state := sFinish }
  }

  private def abort(): Unit = {
    errReg := true.B
    state  := sFinish
  }

  switch(state) {
    is(sIdle) {
      when(startJob) {
        doneReg := false.B
        errReg  := false.B
        enter(0)
      }
    }

    is(sLoadW) {
      when(st.io.rvalid) {
        rowReg  := Cat(st.io.rdata, rowReg(Dim * InWidth - 1, 32))
        rowWord := rowWord + 1.U
        when(rowWord === (RowWords - 1).U) { rowValid := true.B }
      }
      when(seq.io.error) { abort() }
        .elsewhen(st.io.done) { state := sLoadWTail }
    }

    is(sLoadWTail) {
      // The last row may still be on its way into the sequencer.
      when(seq.io.error) { abort() }
        .elsewhen(!rowValid && !seq.io.busy) { enter(1) }
    }

    is(sBias) {
      when(st.io.rvalid) { post.io.load := true.B }
      when(st.io.done) { enter(2) }
    }

    is(sLoadX) {
      when(st.io.rvalid) { xReg := Cat(st.io.rdata, xReg(Dim * InWidth - 1, 32)) }
      when(st.io.done) { state := sCompStart }
    }

    is(sCompStart) {
      seq.io.computeStart := true.B
      state := sCompWait
    }

    is(sCompWait) {
      when(seq.io.done) {
        post.io.add := true.B
        enter(3)
      }.elsewhen(seq.io.error) { abort() }
    }

    is(sPack) {
      // One lane per cycle through PostProc's single activation path,
      // whose result arrives a cycle after the lane was at the ring's head:
      // rotate on cycles 0..Dim-1, collect on cycles 1..Dim.
      when(packCtr =/= 0.U) { actReg := Cat(post.io.act, actReg(Dim * InWidth - 1, InWidth)) }
      when(packCtr =/= Dim.U) { post.io.rotate := true.B }
      packCtr := packCtr + 1.U
      when(packCtr === Dim.U) {
        burst(yPtr, RowWords, write = true)
        state := sStoreAct
      }
    }

    is(sStoreAct) {
      when(st.io.wfire) { actReg := actReg >> 32 }
      when(st.io.done) { state := sFinish }
    }

    is(sStoreRaw) {
      st.io.wdata := post.io.raw
      when(st.io.wfire) { post.io.rotate := true.B }
      when(st.io.done) { state := sFinish }
    }

    is(sFinish) {
      doneReg := true.B
      io.done := true.B
      state   := sIdle
    }
  }
}
