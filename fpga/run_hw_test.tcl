# Drives a full Vmm test over JTAG (weight-matrix load + activation vector +
# capture) via the vio_0/ila_0 debug cores in fpga/vmm_debug.v, then decodes
# and prints all 64 y lanes. Saves doing the 64-row weight load by hand
# through the VIO GUI.
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
#   set W [lrepeat 64 [lrepeat 64 0]]       ;# 64x64 zero matrix to start
#   lset W 0 [lreplace [lindex $W 0] 0 0 5] ;# W[row 0][col 0] = 5
#   set A [lrepeat 64 0]
#   lset A 0 3                              ;# a[0] = 3
#   vmm_run $W $A                           ;# arms, drives, waits, prints y
#
# W: list of 64 rows, each a list of 64 signed ints in [-128, 127].
# A: list of 64 signed ints in [-128, 127].

# ---------------------------------------------------------------------
# CONFIGURE: hw_vio/hw_ila probe names. These match the net names vio_0/
# ila_0 are wired to in fpga/vmm_debug.v. If `get_hw_probes -of_objects
# [get_hw_vios hw_vio_1]` (or the ila equivalent) shows different names on
# your build, update these.
# ---------------------------------------------------------------------
set VIO [get_hw_vios hw_vio_1]
set ILA [get_hw_ilas hw_ila_1]

array set VIO_PROBE {
  weightLoad        vio_weightLoad
  weightRow         vio_weightRow
  weightRowData_lo  vio_weightRowData_lo
  weightRowData_hi  vio_weightRowData_hi
  a_lo              vio_a_lo
  a_hi              vio_a_hi
  aValid            vio_aValid
}
set ILA_PROBE_TRIGGER dut_yValid_1
set ILA_PROBE_Y        dut_y

# ---------------------------------------------------------------------
# bit-twiddling helpers
# ---------------------------------------------------------------------

# Packs 32 signed 8-bit values (source indices $lo..$lo+31) into one
# 256-bit hex string, with index $lo at the low byte -- matches how
# vmm_debug.v reassembles vio_weightRowData/vio_a from the lo/hi halves.
proc pack256 {values lo} {
  set word 0
  for {set i 31} {$i >= 0} {incr i -1} {
    set v [lindex $values [expr {$lo + $i}]]
    if {$v < 0} { set v [expr {$v + 256}] }
    set word [expr {($word << 8) | $v}]
  }
  return [format "%064X" $word]
}

proc from_twos {uval bits} {
  set signbit [expr {1 << ($bits - 1)}]
  if {($uval & $signbit) != 0} {
    return [expr {$uval - (1 << $bits)}]
  }
  return $uval
}

# ---------------------------------------------------------------------
# VIO drive
# ---------------------------------------------------------------------
proc vio_set {name value} {
  global VIO VIO_PROBE
  set p [get_hw_probes "*$VIO_PROBE($name)" -of_objects $VIO]
  set_property OUTPUT_VALUE $value $p
  commit_hw_vio $p
}

proc vmm_load_weights {W} {
  for {set r 0} {$r < 64} {incr r} {
    set row [lindex $W $r]
    vio_set weightRow          [format "%02X" $r]
    vio_set weightRowData_lo   [pack256 $row 0]
    vio_set weightRowData_hi   [pack256 $row 32]
    vio_set weightLoad "1"
    vio_set weightLoad "0"
  }
}

proc vmm_load_activation {A} {
  vio_set a_lo    [pack256 $A 0]
  vio_set a_hi    [pack256 $A 32]
  vio_set aValid "1"
  vio_set aValid "0"
}

# ---------------------------------------------------------------------
# ILA arm / capture / decode
# ---------------------------------------------------------------------

# Arm before driving stimulus -- yValid is a single-cycle pulse, so the
# ILA has to already be waiting before vmm_load_activation raises aValid.
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

  for {set lane 0} {$lane < 64} {incr lane} {
    set uval [expr {($word >> ($lane * 22)) & 0x3FFFFF}]
    puts "y\[$lane\] = [from_twos $uval 22]"
  }
}

proc vmm_run {W A} {
  vmm_arm
  vmm_load_weights $W
  vmm_load_activation $A
  set csv [vmm_capture_csv]
  vmm_decode_y $csv
}
