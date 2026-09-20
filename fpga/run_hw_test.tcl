# Drives a full VmmController test over JTAG (weight-matrix load + activation
# vector + capture) via the vio_0/ila_0 debug cores in fpga/vmm_debug.v, then
# decodes and prints all 32 y lanes. Saves doing the 32-row weight load by
# hand through the VIO GUI.
#
# This drives VmmController's start/stream/start handshake, not the raw Vmm
# core: pulse loadStart, feed 32 rows (one VIO toggle each), pulse
# computeStart, trigger on `done`. The FSM does the per-cycle sequencing.
#
# NOT YET VALIDATED against a live device -- written from the vio_0/ila_0
# CONFIG in fpga/create_debug_ip.tcl and the net names in fpga/vmm_debug.v,
# but the exact hw_probe names Vivado assigns, and the exact CSV column/radix
# format `write_hw_ila_data` produces, can vary by Vivado version. See the
# CONFIGURE section below and the note on vmm_decode_y if something doesn't
# match on first run.
#
# Prerequisites (see fpga/README.md):
#   1. Bitstream programmed.
#   2. PS7 initialized over JTAG via XSCT (FCLK_CLK0 must be toggling before
#      dbg_hub / hw_ila_1 / hw_vio_1 are reachable at all -- this is the step
#      that's easy to miss on a pure-PL bring-up with no OS booted).
#   3. hw_ila_1 / hw_vio_1 showing up under the programmed device in
#      Hardware Manager (refresh_hw_device after PS7 init if they don't).
#
# Usage, from the Hardware Manager Tcl console:
#   source fpga/run_hw_test.tcl
#   set W [lrepeat 32 [lrepeat 32 0]]       ;# 32x32 zero matrix to start
#   lset W 0 [lreplace [lindex $W 0] 0 0 5] ;# W[row 0][col 0] = 5
#   set A [lrepeat 32 0]
#   lset A 0 3                              ;# a[0] = 3
#   vmm_run $W $A                           ;# arms, drives, waits, prints y
#
# W: list of 32 rows, each a list of 32 signed ints in [-8, 7] (INT4).
# A: list of 32 signed ints in [-8, 7] (INT4).

# ---------------------------------------------------------------------
# CONFIGURE: hw_vio/hw_ila probe names. These match the net names vio_0/
# ila_0 are wired to in fpga/vmm_debug.v. If `get_hw_probes -of_objects
# [get_hw_vios hw_vio_1]` (or the ila equivalent) shows different names on
# your build, update these. Vivado often appends a "_1" to ILA probe names.
# ---------------------------------------------------------------------
set VIO [get_hw_vios hw_vio_1]
set ILA [get_hw_ilas hw_ila_1]

array set VIO_PROBE {
  loadStart      vio_loadStart
  weightRowData  vio_weightRowData
  weightRowValid vio_weightRowValid
  computeStart   vio_computeStart
  a              vio_a
}
# probe_in readbacks, for optional progress polling (see vmm_status).
array set VIO_IN_PROBE {
  weightRowReady dut_weightRowReady
  busy           dut_busy
  done           dut_done
  error          dut_error
}
set ILA_PROBE_TRIGGER dut_done
set ILA_PROBE_Y       dut_y

# ---------------------------------------------------------------------
# bit-twiddling helpers
# ---------------------------------------------------------------------

# Packs 32 signed 4-bit values (one full weight row or activation vector)
# into one 128-bit hex string (32 hex nibbles), with index 0 at the
# low nibble -- matches how vmm_debug.v wires vio_weightRowData/vio_a
# straight into the DUT's per-lane ports.
proc pack128 {values} {
  set word 0
  for {set i 31} {$i >= 0} {incr i -1} {
    set v [lindex $values $i]
    if {$v < 0} { set v [expr {$v + 16}] }
    set word [expr {($word << 4) | ($v & 0xF)}]
  }
  return [format "%032X" $word]
}

proc from_twos {uval bits} {
  set signbit [expr {1 << ($bits - 1)}]
  if {($uval & $signbit) != 0} {
    return [expr {$uval - (1 << $bits)}]
  }
  return $uval
}

# ---------------------------------------------------------------------
# VIO drive / readback
# ---------------------------------------------------------------------
proc vio_set {name value} {
  global VIO VIO_PROBE
  set p [get_hw_probes "*$VIO_PROBE($name)" -of_objects $VIO]
  set_property OUTPUT_VALUE $value $p
  commit_hw_vio $p
}

# High->low toggle of a 1-bit VIO output. VmmController rising-edge-detects
# loadStart/computeStart, and vmm_debug.v edge-detects weightRowValid, so
# one toggle == one event regardless of how many fabric cycles elapse
# between the two commits.
proc vio_pulse {name} {
  vio_set $name 1
  vio_set $name 0
}

proc vmm_status {} {
  global VIO VIO_IN_PROBE
  refresh_hw_vio $VIO
  foreach k {weightRowReady busy done error} {
    set p [get_hw_probes "*$VIO_IN_PROBE($k)" -of_objects $VIO]
    puts [format "  %-14s = %s" $k [get_property INPUT_VALUE $p]]
  }
}

proc vmm_load_weights {W} {
  # One rising edge on loadStart -> FSM enters its load state and holds
  # weightRow.ready high for the whole 32-row sequence.
  vio_set weightRowValid 0
  vio_pulse loadStart
  for {set r 0} {$r < 32} {incr r} {
    vio_set weightRowData [pack128 [lindex $W $r]]
    # each toggle -> exactly one Decoupled beat -> one row, FSM auto-addresses
    vio_pulse weightRowValid
  }
}

proc vmm_load_activation {A} {
  vio_set a [pack128 $A]
  vio_pulse computeStart
}

# ---------------------------------------------------------------------
# ILA arm / capture / decode
# ---------------------------------------------------------------------

# Arm before driving the activation -- `done` is a single-cycle pulse, so
# the ILA has to already be waiting before vmm_load_activation fires.
proc vmm_arm {} {
  global ILA ILA_PROBE_TRIGGER
  set_property TRIGGER_COMPARE_VALUE eq1'b1 \
    [get_hw_probes "*$ILA_PROBE_TRIGGER" -of_objects $ILA]
  run_hw_ila $ILA
}

proc vmm_capture_csv {{timeout 30}} {
  global ILA
  wait_on_hw_ila -timeout $timeout $ILA
  set data [upload_hw_ila_data $ILA]
  display_hw_ila_data $data
  set csv [file join [pwd] "vmm_capture.csv"]
  write_hw_ila_data -csv_file $csv -force $data
  return $csv
}

# Pulls the y bus out of the exported CSV. Uses the LAST captured sample
# rather than the exact trigger row -- per Vmm.scala, y holds steady once
# valid (no re-accumulation until the next pass), so the final sample in
# the capture window is safe regardless of trigger position.
#
# NOTE: assumes the dut_y column is either plain hex or plain binary
# digits (no radix prefix beyond an optional 0x/0b, no separators). If
# `write_hw_ila_data`'s default format differs on your Vivado version,
# open vmm_capture.csv once and adjust the parsing below.
proc vmm_decode_y {csv} {
  global ILA_PROBE_Y
  set fh [open $csv r]
  set lines [split [read $fh] "\n"]
  close $fh

  set header [split [lindex $lines 0] ","]
  set col -1
  for {set i 0} {$i < [llength $header]} {incr i} {
    if {[string first "${ILA_PROBE_Y}\[" [lindex $header $i]] >= 0} {
      set col $i
      break
    }
  }
  if {$col < 0} {
    error "'$ILA_PROBE_Y' column not found in $csv -- header was: $header"
  }

  set last {}
  foreach line $lines {
    if {[string trim $line] ne ""} { set last $line }
  }
  set raw [string trim [lindex [split $last ","] $col] "\" "]
  if {$raw eq ""} { set raw "0" }
  set word [expr {"0x$raw"}]

  for {set lane 0} {$lane < 32} {incr lane} {
    set uval [expr {($word >> ($lane * 13)) & 0x1FFF}]
    puts "y\[$lane\] = [from_twos $uval 13]"
  }
}

proc vmm_run {W A} {
  vmm_arm
  vmm_load_weights $W
  vmm_load_activation $A
  set csv [vmm_capture_csv]
  vmm_decode_y $csv
}
