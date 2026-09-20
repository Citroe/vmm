# Minimal block design: ZYNQ7 Processing System (for FCLK_CLK0/FCLK_RESET0_N
# only -- no AXI master/slave connected to vmm_debug) + vmm_debug
# (VmmController + Vmm + MacBank + VIO + ILA). Run in Vivado's Tcl console
# with a project open, targeting the Pynq-Z2 (xc7z020clg400-1).
#
# Required order:
#   1. add_files generated/VmmController.v fpga/vmm_debug.v   (so vmm_debug
#      is a known module reference the block design can instantiate)
#   2. source fpga/create_debug_ip.tcl              (creates vio_0/ila_0,
#      which vmm_debug.v instantiates)
#   3. source fpga/build_bd.tcl                      (this script)
#
# Use generated/VmmController.v here, NOT generated/Vmm.v -- the controller
# file already contains Vmm and MacBank; adding both would define them twice.
# Regenerate it first if stale:
#   sbt "runMain vmm.MainController --target-dir generated"
#
# CAVEAT: the board part VLNV below (tul.com.tw:pynq-z2:part0:1.0) is the
# commonly-used identifier for Pynq-Z2 board files, but board file naming
# can drift between Vivado versions/installs, and the board files may not
# be installed at all (they weren't on the machine this was validated on).
# Skip the preset in that case rather than letting `set_property board_part`
# hard-error -- apply_bd_automation still succeeds without it (it just
# leaves DDR/MIO at generic Zynq-7020 defaults, which is fine since this
# harness never touches DDR).  If you do have the board files, run
# `get_board_parts -filter {NAME =~ "*pynq*"}` to confirm the exact string.

set board_part "tul.com.tw:pynq-z2:part0:1.0"
if {[lsearch [get_board_parts -quiet] $board_part] < 0} {
  puts "WARNING: '$board_part' not found in this Vivado's board file list -- continuing without a board preset."
} else {
  set_property board_part $board_part [current_project]
}

create_bd_design "system"

set ps7 [create_bd_cell -type ip -vlnv xilinx.com:ip:processing_system7 ps7_0]
apply_bd_automation -rule xilinx.com:bd_rule:processing_system7 \
  -config {make_external "FIXED_IO, DDR" apply_board_preset "1" Master "Disable" Slave "Disable"} \
  [get_bd_cells ps7_0]

# FCLK_CLK0 at 100 MHz is enough for a first bring-up; raise it later once
# timing is characterized. The automation's "Master Disable" above does NOT
# actually disable the M_AXI_GP0 port on the PS7 IP itself (confirmed on
# Vivado 2024.1) -- it's still enabled by default, which leaves
# M_AXI_GP0_ACLK unconnected and fails validate_bd_design ("clock pins are
# not connected to a valid clock source"). This harness has no AXI
# master/slave traffic at all, so disable both GP ports directly instead of
# relying on the automation rule for it.
set_property -dict [list \
  CONFIG.PCW_FPGA0_PERIPHERAL_FREQMHZ {100} \
  CONFIG.PCW_USE_FABRIC_INTERRUPT {0} \
  CONFIG.PCW_USE_M_AXI_GP0 {0} \
  CONFIG.PCW_USE_M_AXI_GP1 {0} \
] $ps7

# vmm_debug.v is added as a project source separately (see below); this
# just instantiates it once it's on disk and in the project.
set vmm_debug [create_bd_cell -type module -reference vmm_debug vmm_debug_0]

connect_bd_net [get_bd_pins ps7_0/FCLK_CLK0] [get_bd_pins vmm_debug_0/clk]
connect_bd_net [get_bd_pins ps7_0/FCLK_RESET0_N] [get_bd_pins vmm_debug_0/resetn]

regenerate_bd_layout
# validate_bd_design raises this to a CRITICAL WARNING (not an error --
# confirmed it still synthesizes/routes fine): FCLK_RESET0_N is an
# asynchronous reset wired straight to vmm_debug's resetn with no
# synchronizer. Fine for bring-up; swap in a "Processor System Reset" IP
# block between them if this harness grows beyond a debug scratch build.
validate_bd_design
save_bd_design

set wrapper_path [make_wrapper -files [get_files system.bd] -top]
add_files -norecurse $wrapper_path
set_property top system_wrapper [current_fileset]

puts "Block design ready. Add generated/VmmController.v and fpga/vmm_debug.v"
puts "as design sources (if not already), run fpga/create_debug_ip.tcl first"
puts "if you haven't, then generate the bitstream."
