# Generates the vio_mlp / ila_mlp IP referenced by fpga/mlp_debug.v (probe
# map documented at the top of that file). Named apart from vio_0 / ila_0 so
# the VmmController harness (vmm_debug.v) can live in the same project.
#
# Sourced by fpga/build_mlp.tcl; can also be run on its own against an open
# project, or:
#   vivado -mode batch -source fpga/create_mlp_debug_ip.tcl -tclargs <project.xpr>

if {[llength $argv] > 0 && [llength [get_projects -quiet]] == 0} {
  open_project [lindex $argv 0]
}

foreach ip_name {vio_mlp ila_mlp} {
  if {[llength [get_ips -quiet $ip_name]] > 0} {
    puts "Removing existing $ip_name before regenerating..."
    delete_ip [get_ips $ip_name]
  }
}

create_ip -name vio -vendor xilinx.com -library ip -module_name vio_mlp
set_property -dict [list \
  CONFIG.C_NUM_PROBE_OUT {4} \
  CONFIG.C_NUM_PROBE_IN {12} \
  CONFIG.C_PROBE_OUT0_WIDTH {1} \
  CONFIG.C_PROBE_OUT1_WIDTH {1} \
  CONFIG.C_PROBE_OUT2_WIDTH {4} \
  CONFIG.C_PROBE_OUT3_WIDTH {1} \
  CONFIG.C_PROBE_OUT0_INIT_VAL {0x0} \
  CONFIG.C_PROBE_OUT1_INIT_VAL {0x0} \
  CONFIG.C_PROBE_OUT2_INIT_VAL {0x0} \
  CONFIG.C_PROBE_OUT3_INIT_VAL {0x0} \
  CONFIG.C_PROBE_IN0_WIDTH {1} \
  CONFIG.C_PROBE_IN1_WIDTH {1} \
  CONFIG.C_PROBE_IN2_WIDTH {1} \
  CONFIG.C_PROBE_IN3_WIDTH {1} \
  CONFIG.C_PROBE_IN4_WIDTH {4} \
  CONFIG.C_PROBE_IN5_WIDTH {4} \
  CONFIG.C_PROBE_IN6_WIDTH {4} \
  CONFIG.C_PROBE_IN7_WIDTH {4} \
  CONFIG.C_PROBE_IN8_WIDTH {16} \
  CONFIG.C_PROBE_IN9_WIDTH {16} \
  CONFIG.C_PROBE_IN10_WIDTH {16} \
  CONFIG.C_PROBE_IN11_WIDTH {140} \
] [get_ips vio_mlp]

# One inference is ~2.2k cycles; 4096 samples around the `done` trigger
# shows the tail of a run and the start of the next.
create_ip -name ila -vendor xilinx.com -library ip -module_name ila_mlp
set_property -dict [list \
  CONFIG.C_NUM_OF_PROBES {6} \
  CONFIG.C_PROBE0_WIDTH {1} \
  CONFIG.C_PROBE1_WIDTH {1} \
  CONFIG.C_PROBE2_WIDTH {1} \
  CONFIG.C_PROBE3_WIDTH {4} \
  CONFIG.C_PROBE4_WIDTH {140} \
  CONFIG.C_PROBE5_WIDTH {4} \
  CONFIG.C_DATA_DEPTH {4096} \
  CONFIG.C_TRIGIN_EN {false} \
] [get_ips ila_mlp]

generate_target all [get_ips vio_mlp]
generate_target all [get_ips ila_mlp]

puts "vio_mlp / ila_mlp created."
