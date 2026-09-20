# Timing-closure checkpoint targeting the Pynq-Z2 (xc7z020clg400-1) at the
# 100MHz FCLK0 rate used by fpga/build_bd.tcl's PCW_FPGA0_PERIPHERAL_FREQMHZ.
#
# The prior dim=64/INT8 time-multiplexed design failed timing at 100MHz
# (WNS -14.9ns, see fpga/README.md's history) with an unpipelined
# multiply+accumulate in the critical path. This design's adder tree is
# deliberately pipelined instead. Post-synthesis-only timing (no routing
# delay) is not trustworthy enough to close this question, so this runs a
# full out-of-context place_design/route_design, not just synth_design.
#
# Run fpga/synth_check.tcl first (resource checkpoint) -- this assumes that
# already passed.
#
# Top / source default to the raw Vmm core. Pass -tclargs to time the
# controller wrapper instead (the FSM adds only a handful of registers and
# a Decoupled handshake in front of the same datapath, but the critical
# path is worth re-confirming since the core's setup slack is tight):
#   vivado -mode batch -source fpga/timing_check.tcl \
#     -tclargs VmmController generated/VmmController.v
#
# Usage (project or no-project mode both work):
#   vivado -mode batch -source fpga/timing_check.tcl

set top      [expr {$argc >= 1 ? [lindex $argv 0] : "Vmm"}]
set src_rel  [expr {$argc >= 2 ? [lindex $argv 1] : "generated/Vmm.v"}]
set here     [file dirname [info script]]
set src      [file join $here .. $src_rel]

read_verilog $src

synth_design -mode out_of_context -top $top -part xc7z020clg400-1 -max_dsp 0

# 100MHz, matching PCW_FPGA0_PERIPHERAL_FREQMHZ in fpga/build_bd.tcl.
# Applied post-synth (get_ports needs an open/elaborated design, which
# doesn't exist until synth_design has run) -- persists through
# opt/place/route same as if it'd come from an XDC read pre-synth. The
# clock port is `clock` for both Vmm and VmmController (Chisel's implicit
# clock name).
create_clock -name clk -period 10.000 [get_ports clock]

opt_design
place_design
route_design

set rpt_name [expr {$top eq "Vmm" ? "timing_check_report.rpt" \
                                  : "timing_check_${top}_report.rpt"}]
set rpt [file join $here $rpt_name]
report_timing_summary -file $rpt

puts "---------------------------------------------------------------"
puts "Timing checkpoint (top=$top, src=$src_rel, 100MHz/10ns period), full report: $rpt"
puts "---------------------------------------------------------------"
if {[catch {exec grep -A 4 "Design Timing Summary" $rpt} hit]} {
  puts "Could not grep Design Timing Summary out of $rpt -- open it directly."
} else {
  puts $hit
}
puts "---------------------------------------------------------------"
puts "Expect WNS >= 0 for timing met at 100MHz. If WNS < 0, see README.md's"
puts "timing-closure notes for the quick 20-25MHz diagnostic (if that closes"
puts "timing, this is a retiming/floorplanning problem, not a correctness bug"
puts "in the RTL)."
