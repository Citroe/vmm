# Synthesis-only (no place & route) resource checkpoint, targeting the
# Pynq-Z2 (xc7z020clg400-1).
#
# Exists to resolve two things empirically rather than by hand-estimate or
# disputed recollection (see README.md's LUT-budget section):
#   1. The actual LUT cost of the fully-spatial 32x32 INT4 array (1024
#      4x4 multipliers + pipelined adder trees) against the Zynq-7020's
#      real LUT budget, as reported by the installed Vivado's own part
#      database (`report_utilization`'s "Available" column) -- not the
#      53,200 (datasheet) vs. ~98k (recollection) figures floating around.
#   2. Whether `-max_dsp 0` actually suppresses DSP48E1 inference on the
#      installed Vivado version -- check the DSP48E1 row reads 0, don't
#      assume the switch worked.
#
# Top / source default to the raw Vmm core. Pass -tclargs to check the
# controller wrapper instead (its FSM is tiny, but this confirms the whole
# generated/VmmController.v elaborates and re-confirms the array cost):
#   vivado -mode batch -source fpga/synth_check.tcl \
#     -tclargs VmmController generated/VmmController.v
#
# Usage (project or no-project mode both work -- this doesn't touch an open
# project's fileset). Regenerate the source first if stale:
#   sbt "runMain vmm.Main --target-dir generated"            ;# Vmm.v
#   sbt "runMain vmm.MainController --target-dir generated"  ;# VmmController.v
#   vivado -mode batch -source fpga/synth_check.tcl

set top      [expr {$argc >= 1 ? [lindex $argv 0] : "Vmm"}]
set src_rel  [expr {$argc >= 2 ? [lindex $argv 1] : "generated/Vmm.v"}]
set here     [file dirname [info script]]
set src      [file join $here .. $src_rel]

read_verilog $src

synth_design -mode out_of_context -top $top -part xc7z020clg400-1 -max_dsp 0

# Keep the historical filename for the default (Vmm) run; suffix others.
set rpt_name [expr {$top eq "Vmm" ? "synth_check_utilization.rpt" \
                                  : "synth_check_${top}_utilization.rpt"}]
set rpt [file join $here $rpt_name]
report_utilization -file $rpt

puts "---------------------------------------------------------------"
puts "Resource checkpoint (top=$top, src=$src_rel, -max_dsp 0), full report: $rpt"
puts "---------------------------------------------------------------"
foreach pattern {"CLB LUTs" "DSP48E1" "Slice LUTs" "DSPs"} {
  if {[catch {exec grep -i $pattern $rpt} hit]} {
    continue
  }
  if {$hit ne ""} {
    puts $hit
  }
}
puts "---------------------------------------------------------------"
puts "Expect the DSP48E1/DSPs line to read 0 -- if it doesn't, -max_dsp 0 did"
puts "not suppress DSP inference on this Vivado version; fall back to"
puts "per-cell (* use_dsp = \"no\" *) attributes injected into the generated"
puts "Verilog (see README.md) instead."
