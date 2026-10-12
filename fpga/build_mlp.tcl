# One-shot bitstream build of the MLP bring-up harness (fpga/mlp_debug.v) for
# the Pynq-Z2 (xc7z020clg400-1): creates a project under fpga/build/mlp_debug,
# adds the sources and ROM images, generates the VIO/ILA, wraps mlp_debug in
# a block design with the PS7 (for FCLK_CLK0 / FCLK_RESET0_N only, 100 MHz),
# and runs synthesis + implementation + write_bitstream.
#
# Regenerate the inputs first if stale:
#   sbt "runMain vmm.MainCim --target-dir generated"   ;# generated/CimSystem.v, tcdm_bank.v
#   python3 fpga/gen_mlp_mem.py                        ;# fpga/mem/*.mem
#
# Usage, from the repo root:
#   vivado -mode batch -source fpga/build_mlp.tcl
#   vivado -mode batch -source fpga/build_mlp.tcl -tclargs nobit  ;# set up only
#
# Outputs: fpga/build/mlp_debug/mlp_debug.runs/impl_1/system_wrapper.{bit,ltx}
# and fpga/mlp_debug_{timing,utilization}.rpt. Then see MANUAL_SETUP.md
# (PS7 init over XSCT, program, Hardware Manager) and run_mlp_hw_test.tcl.

set here     [file normalize [file dirname [info script]]]
set root     [file normalize [file join $here ..]]
set proj_dir [file join $here build mlp_debug]
set run_impl [expr {[lindex $argv 0] ne "nobit"}]

create_project -force mlp_debug $proj_dir -part xc7z020clg400-1

# Board preset is optional (see build_bd.tcl's note): this harness uses no
# DDR/MIO, only FCLK0.
set board_part "tul.com.tw:pynq-z2:part0:1.0"
if {[lsearch [get_board_parts -quiet] $board_part] >= 0} {
  set_property board_part $board_part [current_project]
} else {
  puts "WARNING: '$board_part' not installed -- continuing without a board preset."
}

add_files -norecurse [list \
  [file join $root generated CimSystem.v] \
  [file join $root generated tcdm_bank.v] \
  [file join $here mlp_debug_core.v] \
  [file join $here mlp_debug.v] \
]
# $readmemh in mlp_debug_core.v and the TCDM banks in CimSystem.v name these
# by bare filename; Vivado resolves them from the project's
# memory-initialisation files.
set mems [glob [file join $here mem *.mem]]
add_files -norecurse $mems
set_property file_type {Memory Initialization Files} [get_files $mems]

source [file join $here create_mlp_debug_ip.tcl]

create_bd_design "system"
set ps7 [create_bd_cell -type ip -vlnv xilinx.com:ip:processing_system7 ps7_0]
apply_bd_automation -rule xilinx.com:bd_rule:processing_system7 \
  -config {make_external "FIXED_IO, DDR" apply_board_preset "1" Master "Disable" Slave "Disable"} \
  [get_bd_cells ps7_0]
# No AXI traffic: disable both GP ports outright (the automation's "Master
# Disable" leaves M_AXI_GP0 enabled with a dangling clock -- see build_bd.tcl).
set_property -dict [list \
  CONFIG.PCW_FPGA0_PERIPHERAL_FREQMHZ {100} \
  CONFIG.PCW_USE_FABRIC_INTERRUPT {0} \
  CONFIG.PCW_USE_M_AXI_GP0 {0} \
  CONFIG.PCW_USE_M_AXI_GP1 {0} \
] $ps7

create_bd_cell -type module -reference mlp_debug mlp_debug_0
connect_bd_net [get_bd_pins ps7_0/FCLK_CLK0]     [get_bd_pins mlp_debug_0/clk]
connect_bd_net [get_bd_pins ps7_0/FCLK_RESET0_N] [get_bd_pins mlp_debug_0/resetn]

validate_bd_design
save_bd_design

set wrapper [make_wrapper -files [get_files system.bd] -top]
add_files -norecurse $wrapper
set_property top system_wrapper [current_fileset]
update_compile_order -fileset sources_1

# Same choice as synth_check.tcl / timing_check.tcl: every multiplier in LUT
# fabric, no DSP48s (the compute-in-memory-style array is the point).
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
report_timing_summary -file [file join $here mlp_debug_timing.rpt]
report_utilization    -file [file join $here mlp_debug_utilization.rpt]

puts "---------------------------------------------------------------"
puts "Bitstream: [file join $proj_dir mlp_debug.runs impl_1 system_wrapper.bit]"
puts "WNS = [get_property SLACK [get_timing_paths -setup]] ns, \
WHS = [get_property SLACK [get_timing_paths -hold]] ns"
puts "---------------------------------------------------------------"
