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
# Any further arguments are more source files; `.mem` files are read as
# memory images. The whole CIM system (the TCDM bank is a separate Verilog
# file, initialised from fpga/mem):
#   vivado -mode batch -source fpga/timing_check.tcl -tclargs CimSystem \
#     generated/CimSystem.v generated/tcdm_bank.v fpga/mem/tcdm_bank0.mem \
#     fpga/mem/tcdm_bank1.mem fpga/mem/tcdm_bank2.mem fpga/mem/tcdm_bank3.mem
#
# Usage (project or no-project mode both work):
#   vivado -mode batch -source fpga/timing_check.tcl

set top      [expr {$argc >= 1 ? [lindex $argv 0] : "Vmm"}]
set src_rel  [expr {$argc >= 2 ? [lrange $argv 1 end] : "generated/Vmm.v"}]
set here     [file dirname [info script]]

foreach f $src_rel {
  set path [file join $here .. $f]
  if {[file extension $f] eq ".mem"} { read_mem $path } else { read_verilog $path }
}

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

# Post-route utilization, and the failing setup endpoints grouped by
# (startpoint -> endpoint) register family with indices collapsed to '#', so
# a failing run shows which structures are slow instead of one worst path.
set tag [expr {$top eq "Vmm" ? "" : "_${top}"}]
report_utilization -file [file join $here "timing_check${tag}_utilization.rpt"]
set fail_rpt [file join $here "timing_check${tag}_failing.rpt"]
array set cnt {}
array set worst {}
foreach p [get_timing_paths -setup -nworst 1 -max_paths 100000 -slack_lesser_than 0] {
  set s [get_property STARTPOINT_PIN $p]
  set e [get_property ENDPOINT_PIN $p]
  regsub -all {[0-9]+} $s "#" s
  regsub -all {[0-9]+} $e "#" e
  set k "$s -> $e"
  set sl [get_property SLACK $p]
  incr cnt($k)
  if {![info exists worst($k)] || $sl < $worst($k)} { set worst($k) $sl }
}
set rows {}
foreach k [array names cnt] { lappend rows [list $cnt($k) $worst($k) $k] }
set fh [open $fail_rpt w]
puts $fh "endpoints  worst_slack  startpoint -> endpoint"
foreach r [lsort -integer -decreasing -index 0 $rows] {
  puts $fh [format "%9d  %11.3f  %s" [lindex $r 0] [lindex $r 1] [lindex $r 2]]
}
close $fh

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
