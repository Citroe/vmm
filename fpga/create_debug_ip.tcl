# Generates the vio_0 / ila_0 IP referenced by fpga/vmm_debug.v.
# Run from the Tcl console of an open Vivado project targeting the Pynq-Z2
# (xc7z020clg400-1), e.g.:
#   vivado -mode batch -source fpga/create_debug_ip.tcl -tclargs <project.xpr>
# or paste into the Tcl console with a project already open.
#
# No -version pinned on either IP: Vivado resolves to whatever vio/ila
# version ships with the installed toolchain, since C_xxx CONFIG properties
# are stable across the versions in common use for these two cores.
#
# Probe map matches fpga/vmm_debug.v, which now drives VmmController (the
# sequencing FSM), not the raw Vmm core -- so the stimulus side is just the
# start/stream/start handshake, and there's no weightRow address probe
# (the FSM addresses rows itself).

if {[llength $argv] > 0} {
  open_project [lindex $argv 0]
}

# Re-running after a failed/updated config leaves a stale IP behind that
# blocks re-creation with "already in use" -- drop it first if present.
foreach ip_name {vio_0 ila_0} {
  if {[llength [get_ips -quiet $ip_name]] > 0} {
    puts "Removing existing $ip_name before regenerating..."
    delete_ip [get_ips $ip_name]
  }
}

# --- VIO: 5 probe_out (stimulus into VmmController), 4 probe_in (status
# readback: weightRow.ready / busy / done / error).
# VIO caps a single probe at 256 bits (unlike ILA, which allows up to
# 4096); weightRowData and a are 128 bits each, one probe apiece.
#   probe_out0  1   loadStart
#   probe_out1  128 weightRowData  (one 32-lane INT4 row)
#   probe_out2  1   weightRowValid (this wrapper edge-detects it -> 1 row per toggle)
#   probe_out3  1   computeStart
#   probe_out4  128 a
#   probe_in0   1   weightRow.ready
#   probe_in1   1   busy
#   probe_in2   1   done
#   probe_in3   1   error
create_ip -name vio -vendor xilinx.com -library ip -module_name vio_0
set_property -dict [list \
  CONFIG.C_NUM_PROBE_OUT {5} \
  CONFIG.C_NUM_PROBE_IN {4} \
  CONFIG.C_PROBE_IN0_WIDTH {1} \
  CONFIG.C_PROBE_IN1_WIDTH {1} \
  CONFIG.C_PROBE_IN2_WIDTH {1} \
  CONFIG.C_PROBE_IN3_WIDTH {1} \
  CONFIG.C_PROBE_OUT0_WIDTH {1} \
  CONFIG.C_PROBE_OUT1_WIDTH {128} \
  CONFIG.C_PROBE_OUT2_WIDTH {1} \
  CONFIG.C_PROBE_OUT3_WIDTH {1} \
  CONFIG.C_PROBE_OUT4_WIDTH {128} \
  CONFIG.C_PROBE_OUT0_INIT_VAL {0x0} \
  CONFIG.C_PROBE_OUT1_INIT_VAL {0x0} \
  CONFIG.C_PROBE_OUT2_INIT_VAL {0x0} \
  CONFIG.C_PROBE_OUT3_INIT_VAL {0x0} \
  CONFIG.C_PROBE_OUT4_INIT_VAL {0x0} \
] [get_ips vio_0]

# --- ILA: done (trigger), packed y (32 x 13b = 416b), plus busy/error/
# computeStart for correlating the capture against FSM state. 416 bits on
# probe1 is within ILA's (much larger than VIO's) per-probe width cap.
#   probe0  1   done  (trigger source)
#   probe1  416 y     (packed, 32 x 13b lanes)
#   probe2  1   busy
#   probe3  1   error
#   probe4  1   computeStart
create_ip -name ila -vendor xilinx.com -library ip -module_name ila_0
set_property -dict [list \
  CONFIG.C_NUM_OF_PROBES {5} \
  CONFIG.C_PROBE0_WIDTH {1} \
  CONFIG.C_PROBE1_WIDTH {416} \
  CONFIG.C_PROBE2_WIDTH {1} \
  CONFIG.C_PROBE3_WIDTH {1} \
  CONFIG.C_PROBE4_WIDTH {1} \
  CONFIG.C_DATA_DEPTH {1024} \
  CONFIG.C_TRIGIN_EN {false} \
  CONFIG.C_ADV_TRIGGER {true} \
] [get_ips ila_0]

generate_target all [get_ips vio_0]
generate_target all [get_ips ila_0]

puts "vio_0 / ila_0 created -- add fpga/vmm_debug.v and generated/VmmController.v as design sources next."
