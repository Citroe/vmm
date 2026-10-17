# Drives a full VmmController test over JTAG (weight-matrix load + activation
# vector + capture) via the vio_0/ila_0 debug cores in fpga/vmm_debug.v,
# decodes all 32 y lanes from the ILA capture, and checks them against
# a^T*W computed here.
#
# This drives VmmController's start/stream/start handshake, not the raw Vmm
# core: pulse loadStart, feed 32 rows (one VIO toggle each), pulse
# computeStart, trigger on `done`. The FSM does the per-cycle sequencing.
# The rows arrive milliseconds apart; generated/VmmController.v is emitted
# with a ~10.7 s load-stall timeout for exactly that (see Main.scala) --
# sim/tb_vmm_debug.v replays this script's timing in simulation.
#
# Prerequisites (see fpga/README.md):
#   1. Bitstream from fpga/build_vmm.tcl programmed with its .ltx.
#   2. PS7 initialised over XSCT (FCLK_CLK0 must be toggling before the
#      debug hub / VIO / ILA are reachable at all).
#   3. Refresh Device in Hardware Manager; hw_vio / hw_ila show up.
#
# Usage, from the Hardware Manager Tcl console (Vivado, not XSCT):
#   source fpga/run_hw_test.tcl
#   vmm_selftest                            ;# 3 matrices, prints PASS/FAIL
#
#   set W [lrepeat 32 [lrepeat 32 0]]       ;# or your own: 32x32 zero matrix
#   lset W 0 0 5                            ;# W[row 0][col 0] = 5
#   set A [lrepeat 32 0]
#   lset A 0 3                              ;# a[0] = 3
#   vmm_run $W $A                           ;# loads, computes, checks, prints y
#
# W: list of 32 rows, each a list of 32 signed ints in [-8, 7] (INT4); row r
# column c is W[r][c], and y[c] = sum_r a[r] * W[r][c].
# A: list of 32 signed ints in [-8, 7] (INT4).

# ---------------------------------------------------------------------
# Debug cores and probes. Vivado names each probe after the net it is wired
# to; a net that feeds both the VIO and the ILA (done, busy, error,
# computeStart here) gets a "_1" suffix on its ILA copy, so lookups accept
# either form.
# ---------------------------------------------------------------------
set VIO [lindex [get_hw_vios -of_objects [current_hw_device]] 0]
set ILA [lindex [get_hw_ilas -of_objects [current_hw_device]] 0]
if {$VIO eq "" || $ILA eq ""} {
  error "no hw_vio / hw_ila on [current_hw_device] -- program with the .ltx, run ps7_init in XSCT, then Refresh Device"
}

array set VIO_PROBE {
  loadStart      vio_loadStart
  weightRowData  vio_weightRowData
  weightRowValid vio_weightRowValid
  computeStart   vio_computeStart
  a              vio_a
  weightRowReady dut_weightRowReady
  busy           dut_busy
  done           dut_done
  error          dut_error
}
set ILA_PROBE_TRIGGER dut_done
set ILA_PROBE_Y       dut_y

proc vmm_probe {core net} {
  foreach pat [list "*/$net" "*/${net}_1"] {
    set p [get_hw_probes -quiet $pat -of_objects $core]
    if {[llength $p] == 1} { return $p }
  }
  error "probe '$net' not found on $core; it has: [get_hw_probes -of_objects $core]"
}

# ---------------------------------------------------------------------
# bit-twiddling helpers
# ---------------------------------------------------------------------

# Packs 32 signed 4-bit values (one weight row or the activation vector)
# into a 32-nibble hex string, index 0 in the low nibble -- matches how
# vmm_debug.v slices vio_weightRowData / vio_a into the DUT's lanes.
proc pack128 {values} {
  set hex ""
  for {set i 31} {$i >= 0} {incr i -1} {
    append hex [format %X [expr {[lindex $values $i] & 0xF}]]
  }
  return $hex
}

proc from_twos {uval bits} {
  if {$uval & (1 << ($bits - 1))} { return [expr {$uval - (1 << $bits)}] }
  return $uval
}

proc vmm_golden {W A} {
  set y {}
  for {set c 0} {$c < 32} {incr c} {
    set s 0
    for {set r 0} {$r < 32} {incr r} {
      incr s [expr {[lindex $A $r] * [lindex $W $r $c]}]
    }
    lappend y $s
  }
  return $y
}

# ---------------------------------------------------------------------
# VIO drive / readback
# ---------------------------------------------------------------------
proc vio_set {name value} {
  global VIO VIO_PROBE
  set p [vmm_probe $VIO $VIO_PROBE($name)]
  set_property OUTPUT_VALUE_RADIX HEX $p
  set_property OUTPUT_VALUE $value $p
  commit_hw_vio $p
}

proc vio_get {name} {
  global VIO VIO_PROBE
  set p [vmm_probe $VIO $VIO_PROBE($name)]
  refresh_hw_vio $VIO
  return [get_property INPUT_VALUE $p]
}

# High->low toggle of a 1-bit VIO output. VmmController rising-edge-detects
# loadStart/computeStart, and vmm_debug.v edge-detects weightRowValid, so
# one toggle == one event however many fabric cycles pass between commits.
proc vio_pulse {name} {
  vio_set $name 1
  vio_set $name 0
}

proc vmm_status {} {
  foreach k {weightRowReady busy done error} {
    puts [format "  %-14s = %s" $k [vio_get $k]]
  }
}

proc vmm_load_weights {W} {
  vio_set weightRowValid 0
  vio_pulse loadStart
  for {set r 0} {$r < 32} {incr r} {
    vio_set weightRowData [pack128 [lindex $W $r]]
    vio_pulse weightRowValid
  }
  if {[vio_get busy] || [vio_get error]} {
    vmm_status
    error "weight load did not complete cleanly (busy/error above)"
  }
}

proc vmm_load_activation {A} {
  vio_set a [pack128 $A]
  vio_pulse computeStart
}

# ---------------------------------------------------------------------
# ILA arm / capture / decode
# ---------------------------------------------------------------------

# Arm before the compute -- `done` is a single-cycle pulse, so the ILA has
# to already be waiting. It only pulses at the end of a compute pass, never
# after a load, so arming before the load is fine too.
proc vmm_arm {} {
  global ILA ILA_PROBE_TRIGGER
  set_property TRIGGER_COMPARE_VALUE eq1'b1 [vmm_probe $ILA $ILA_PROBE_TRIGGER]
  run_hw_ila $ILA
}

proc vmm_capture_csv {} {
  global ILA
  wait_on_hw_ila -timeout 1 $ILA
  set data [upload_hw_ila_data $ILA]
  display_hw_ila_data $data
  set csv [file join [pwd] "vmm_capture.csv"]
  write_hw_ila_data -csv_file $csv -force $data
  return $csv
}

# Returns the 32 y lanes from the LAST captured sample: y is registered and
# holds from `done` until the next pass, so every sample after the trigger
# carries it. write_hw_ila_data's CSV is: a header row of probe names (y is
# ".../dut_y[415:0]"), a "Radix - ..." row (HEX for y), then one row per
# sample.
proc vmm_decode_y {csv} {
  global ILA_PROBE_Y
  set fh [open $csv r]
  set lines [split [string trim [read $fh]] "\n"]
  close $fh

  set header [split [lindex $lines 0] ","]
  set col -1
  for {set i 0} {$i < [llength $header]} {incr i} {
    if {[regexp "/${ILA_PROBE_Y}(_1)?\\\[" [lindex $header $i]]} { set col $i; break }
  }
  if {$col < 0} { error "no '$ILA_PROBE_Y' column in $csv -- header: $header" }

  set raw [string trim [lindex [split [lindex $lines end] ","] $col] "\" \r"]
  set word [expr {"0x$raw"}]
  set y {}
  for {set lane 0} {$lane < 32} {incr lane} {
    lappend y [from_twos [expr {($word >> ($lane * 13)) & 0x1FFF}] 13]
  }
  return $y
}

# Full pass: load W, compute with A, capture, compare against a^T*W.
# Returns 1 on a match.
proc vmm_run {W A {label ""}} {
  vmm_load_weights $W
  vmm_arm
  vmm_load_activation $A
  set y   [vmm_decode_y [vmm_capture_csv]]
  set exp [vmm_golden $W $A]
  set bad 0
  for {set c 0} {$c < 32} {incr c} {
    set mark ""
    if {[lindex $y $c] != [lindex $exp $c]} { set mark "  <-- expected [lindex $exp $c]"; incr bad }
    if {$label eq "" || $mark ne ""} { puts "  y\[$c\] = [lindex $y $c]$mark" }
  }
  if {[vio_get error]} { puts "  error flag is set"; incr bad }
  puts [format "%s%s" [expr {$label eq "" ? "" : "$label: "}] \
    [expr {$bad ? "MISMATCH ($bad)" : "match (y\[0..2\] = [lrange $y 0 2])"}]]
  return [expr {!$bad}]
}

# The three matrices of sim/tb_vmm_debug.v: a mixed pattern, and the two
# full-scale corners (-8*-8 -> +2048, 7*-8 -> -1792 in every lane).
proc vmm_selftest {} {
  set W {}; set A {}
  for {set r 0} {$r < 32} {incr r} {
    set row {}
    for {set c 0} {$c < 32} {incr c} { lappend row [expr {($r*7 + $c*3 + 5) % 9 - 4}] }
    lappend W $row
    lappend A [expr {($r*13 + 4) % 9 - 4}]
  }
  set ok [vmm_run $W $A "mixed"]
  set ok [expr {[vmm_run [lrepeat 32 [lrepeat 32 -8]] [lrepeat 32 -8] "all -8 x -8"] && $ok}]
  set ok [expr {[vmm_run [lrepeat 32 [lrepeat 32 7]]  [lrepeat 32 -8] "all 7 x -8"] && $ok}]
  puts [expr {$ok ? "PASS: all 3 matrices match a^T*W" : "FAIL"}]
  return $ok
}
