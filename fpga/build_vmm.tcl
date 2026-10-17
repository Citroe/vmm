# One-shot bitstream build of the standalone VmmController harness
# (fpga/vmm_debug.v) for the Pynq-Z2 (xc7z020clg400-1): creates a project
# under fpga/build/vmm_debug, adds the sources, generates vio_0 / ila_0
# (create_debug_ip.tcl), builds the PS7 + vmm_debug block design
# (build_bd.tcl), and runs synthesis + implementation + write_bitstream.
#
# Regenerate the input first if stale:
#   sbt "runMain vmm.MainController --target-dir generated"   ;# VmmController.v
#
# Usage, from the repo root:
#   vivado -mode batch -source fpga/build_vmm.tcl
#   vivado -mode batch -source fpga/build_vmm.tcl -tclargs nobit  ;# set up only
#
# Outputs: fpga/build/vmm_debug/vmm_debug.runs/impl_1/system_wrapper.{bit,ltx}
# and fpga/vmm_debug_{timing,utilization}.rpt. Then see README.md (program,
# PS7 init over XSCT, Hardware Manager) and run_hw_test.tcl.

set here     [file normalize [file dirname [info script]]]
set root     [file normalize [file join $here ..]]
set proj_dir [file join $here build vmm_debug]
set run_impl [expr {[lindex $argv 0] ne "nobit"}]

create_project -force vmm_debug $proj_dir -part xc7z020clg400-1

# Use generated/VmmController.v, NOT generated/Vmm.v -- it already contains
# Vmm and MacBank (see build_bd.tcl).
add_files -norecurse [list \
  [file join $root generated VmmController.v] \
  [file join $here vmm_debug.v] \
]

# create_debug_ip.tcl opens [lindex $argv 0] as a project when given one;
# hide this script's own args (e.g. "nobit") from it.
set saved_argv $argv
set argv {}
source [file join $here create_debug_ip.tcl]
source [file join $here build_bd.tcl]
set argv $saved_argv
update_compile_order -fileset sources_1

# Same choice as synth_check.tcl / timing_check.tcl / build_mlp.tcl: every
# multiplier in LUT fabric, no DSP48s.
set_property -name {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} -value {-max_dsp 0} \
  -objects [get_runs synth_1]

if {!$run_impl} {
  puts "Project set up at $proj_dir (nobit: not launching runs)."
  return
}

launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
  error "impl_1 did not complete: [get_property STATUS [get_runs impl_1]]"
}

open_run impl_1
report_timing_summary -file [file join $here vmm_debug_timing.rpt]
report_utilization    -file [file join $here vmm_debug_utilization.rpt]

puts "---------------------------------------------------------------"
puts "Bitstream: [file join $proj_dir vmm_debug.runs impl_1 system_wrapper.bit]"
puts "WNS = [get_property SLACK [get_timing_paths -setup]] ns, \
WHS = [get_property SLACK [get_timing_paths -hold]] ns"
puts "---------------------------------------------------------------"
