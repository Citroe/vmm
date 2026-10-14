# Runs the MLP bring-up harness (fpga/mlp_debug.v) over JTAG through its VIO
# and prints pass/fail against the golden results held in the bitstream.
# Nothing is downloaded: weights and biases are the TCDM's initial contents
# and the 16 test images are a ROM.
#
# Validated on a Pynq-Z2 2026-10-15 (Vivado 2024.1): all 16 golden images
# pass, 8185 cycles per inference. The hw_probe names Vivado assigns can
# still differ by version; if a probe is not found, list them with
#   get_hw_probes -of_objects [get_hw_vios]
# and adjust MLP_PROBE below.
#
# Prerequisites: bitstream from fpga/build_mlp.tcl programmed with its .ltx
# probes file, and the PS7 initialised over XSCT so FCLK_CLK0 is running
# (see fpga/README.md / MANUAL_SETUP.md -- without it the debug hub and the
# VIO do not appear at all).
#
# Usage, from the Hardware Manager Tcl console:
#   source fpga/run_mlp_hw_test.tcl
#   mlp_run_all        ;# all 16 golden images, prints PASS/FAIL
#   mlp_run 5          ;# one image, prints class + logits
#   mlp_status         ;# raw VIO readback
#   mlp_reset          ;# reset the harness FSM (TCDM contents are kept)

# Pick the VIO that carries the harness's probes, whatever index Vivado
# gave it.
set MLP_VIO [lindex [get_hw_vios -of_objects [current_hw_device]] 0]

array set MLP_PROBE {
  run          vio_run
  run_all      vio_run_all
  img_sel      vio_img_sel
  soft_reset   vio_soft_reset
  ready        core_ready
  result_valid core_result_valid
  error        core_error
  match        core_match
  cls          core_cls
  exp_cls      core_exp_cls
  exp_label    core_exp_label
  cur_img      core_cur_img
  pass_mask    core_pass_mask
  done_mask    core_done_mask
  cycles       core_cycles
  logits       core_logits
}

proc mlp_probe {name} {
  global MLP_VIO MLP_PROBE
  set p [get_hw_probes -quiet "*$MLP_PROBE($name)" -of_objects $MLP_VIO]
  if {[llength $p] != 1} {
    error "probe '$MLP_PROBE($name)' matched [llength $p] hw_probes -- see the note at the top of this script"
  }
  return $p
}

proc mlp_set {name value} {
  set p [mlp_probe $name]
  set_property OUTPUT_VALUE $value $p
  commit_hw_vio $p
}

# Unsigned integer value of a VIO input probe.
proc mlp_get {name} {
  global MLP_VIO
  set p [mlp_probe $name]
  set_property INPUT_VALUE_RADIX HEX $p
  refresh_hw_vio $MLP_VIO
  return [expr {"0x[get_property INPUT_VALUE $p]"}]
}

proc mlp_pulse {name} {
  mlp_set $name 1
  mlp_set $name 0
}

# An inference is ~82 us (there is no boot phase), so `ready` is normally back
# long before the first JTAG poll; the loop is only a guard.
proc mlp_wait_ready {{tries 50}} {
  for {set i 0} {$i < $tries} {incr i} {
    if {[mlp_get ready]} { return }
    after 100
  }
  error "harness never became ready (error=[mlp_get error]) -- is FCLK_CLK0 running?"
}

proc mlp_logits {} {
  set word [mlp_get logits]
  set out {}
  for {set i 0} {$i < 10} {incr i} {
    set v [expr {($word >> (14 * $i)) & 0x3FFF}]
    if {$v & 0x2000} { set v [expr {$v - 0x4000}] }
    lappend out $v
  }
  return $out
}

proc mlp_status {} {
  foreach k {ready result_valid error match cur_img cls exp_cls exp_label cycles} {
    puts [format "  %-12s = %d" $k [mlp_get $k]]
  }
  puts [format "  %-12s = %04X" pass_mask [mlp_get pass_mask]]
  puts [format "  %-12s = %04X" done_mask [mlp_get done_mask]]
  puts [format "  %-12s = %s" logits [mlp_logits]]
}

proc mlp_reset {} {
  mlp_set soft_reset 1
  mlp_set soft_reset 0
  mlp_wait_ready
}

proc mlp_run {idx} {
  mlp_wait_ready
  mlp_set img_sel [format %X $idx]
  mlp_pulse run
  mlp_wait_ready
  set ok [mlp_get match]
  puts [format "image %2d: class %d (golden %d, label %d), %d cycles, logits %s -- %s" \
    $idx [mlp_get cls] [mlp_get exp_cls] [mlp_get exp_label] [mlp_get cycles] \
    [mlp_logits] [expr {$ok ? "match" : "MISMATCH"}]]
  return $ok
}

proc mlp_run_all {} {
  mlp_wait_ready
  mlp_pulse run_all
  mlp_wait_ready
  set pass [mlp_get pass_mask]
  set done [mlp_get done_mask]
  set err  [mlp_get error]
  puts [format "done_mask = %04X, pass_mask = %04X, error = %d" $done $pass $err]
  if {$done == 0xFFFF && $pass == 0xFFFF && !$err} {
    puts "PASS: all 16 golden images match (class and all 10 logits)"
    return 1
  }
  for {set i 0} {$i < 16} {incr i} {
    if {!(($pass >> $i) & 1)} { puts "  image $i did not match -- try: mlp_run $i" }
  }
  puts "FAIL"
  return 0
}
