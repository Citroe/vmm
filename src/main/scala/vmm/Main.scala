package vmm

// The circt/firtool-based ChiselStage needs an external `firtool` binary
// version-matched to this Chisel release (3.6.0 -> firtool ~1.37.0); the
// only firtool builds available on this machine are much newer CIRCT
// nightlies that dropped a flag chisel3 3.6.0 still passes (`-dedup`).
// Falling back to the classic, dependency-free Scala FIRRTL Compiler
// avoids that version skew entirely -- swap this back to
// circt.stage.ChiselStage once a matching firtool is available (e.g. once
// this generator moves into Chipyard, which pins one).
object Main extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Vmm(VmmParams()), args)
}

/** Emits `VmmController.v` -- the sequencing FSM plus the `Vmm`/`MacBank`
  * hierarchy it wraps, all in one file. This is the top the FPGA bring-up
  * flow (fpga/vmm_debug.v, fpga/build_bd.tcl) uses; `generated/Vmm.v` from
  * `Main` above stays around for the core-only paths (sim/tb_vmm.v,
  * fpga/synth_check.tcl / timing_check.tcl with no top override). Do not add
  * both files to the same Vivado project -- `Vmm`/`MacBank` are defined in
  * each and would collide.
  *
  *   sbt "runMain vmm.MainController --target-dir generated"
  */
object MainController extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new VmmController(VmmParams()), args)
}

/** Emits `MlpEngine.v` -- the Phase 3 tile-scheduler + VmmController/Vmm
  * hierarchy, all in one file. This is the top Phase 5 FPGA bring-up
  * (`mlp_debug.v`) will target; see `vmm.mlp.MlpEngine`'s doc for the
  * loading/inference protocol.
  *
  *   sbt "runMain vmm.MainMlp --target-dir generated"
  */
object MainMlp extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new vmm.mlp.MlpEngine(), args)
}
