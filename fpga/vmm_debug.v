`timescale 1ns / 1ps
//
// Isolated hardware bring-up harness for VmmController (the sequencing FSM
// around Vmm; dim=32, inWidth=4, accWidth=13) on the Pynq-Z2
// (xc7z020clg400-1). No AXI/RoCC/TileLink wrapper, no board I/O -- stimulus
// and capture go over JTAG via VIO (drive) and ILA (observe), so this
// exercises exactly the controller's start/busy/done handshake with nothing
// else in the datapath.
//
// This drives VmmController, not the raw Vmm core: the 32-cycle weight-load
// sequencing and the aValid-pulse / yValid-wait timing all live inside the
// FSM now, so JTAG only has to pulse loadStart, hand over 32 rows, pulse
// computeStart, and watch done. The core-only harness (raw Vmm over VIO/ILA)
// is git history; see sim/tb_vmm.v for a core-only testbench.
//
// This only needs a clock/reset -- on Zynq the PL fabric has no standalone
// oscillator, so `clk`/`resetn` here are expected to come from a Zynq7
// Processing System's FCLK_CLK0 / FCLK_RESET0_N (instantiate PS7 via a
// block design and wire its FCLK0 output in; see create_debug_ip.tcl for
// the VIO/ILA IP generation that matches the probe widths used below).
//
// Driving protocol (see src/main/scala/vmm/VmmController.scala):
//   1. Raise vio_loadStart for >=1 clock, then lower it. VmmController
//      rising-edge-detects it internally, so holding it high is harmless --
//      it starts exactly one load.
//   2. For each of the 32 rows, row 0 first: set vio_weightRowData, then
//      pulse vio_weightRowValid high->low. This wrapper edge-detects
//      vio_weightRowValid into a single-cycle Decoupled `valid` beat, so
//      each high->low pulse feeds exactly one row (a VIO level held high
//      would otherwise be re-consumed every fabric cycle, since the FSM's
//      weightRow.ready stays high through the whole load). Watch
//      vio_weightRowReady / vio_busy to confirm progress; busy drops when
//      the 32nd row is accepted.
//   3. Set vio_a, pulse vio_computeStart high->low (also internally
//      edge-detected).
//   4. Watch ila_0's done probe (probe0) for the capture trigger; y is
//      probe1, valid and held from that point until the next pass.
//   vio_error (probe_in) / ILA probe3 go high if a compute pass is started
//      but the core never raises yValid -- i.e. the FSM could not drive it.
//
module vmm_debug (
  input wire clk,
  input wire resetn
);

  wire reset = ~resetn;

  // ------------------------------------------------------------------
  // VIO: JTAG-driven stimulus (probe_out = FPGA input) + status readback
  // (probe_in = FPGA output back to the VIO dashboard).
  // ------------------------------------------------------------------
  wire         vio_loadStart;          // probe_out0[0]
  // At dim=32/inWidth=4, weightRowData and a are each 128 bits (32 lanes x
  // 4b) -- within VIO's 256-bit single-probe cap, one probe each.
  wire [127:0] vio_weightRowData;      // probe_out1
  wire         vio_weightRowValid;     // probe_out2[0]
  wire         vio_computeStart;       // probe_out3[0]
  wire [127:0] vio_a;                  // probe_out4

  wire         dut_weightRowReady;     // probe_in0[0]
  wire         dut_busy;               // probe_in1[0]
  wire         dut_done;               // probe_in2[0]
  wire         dut_error;              // probe_in3[0]

  wire [415:0] dut_y;                  // 32 x 13-bit lanes

  vio_0 vio_inst (
    .clk(clk),
    .probe_in0(dut_weightRowReady),
    .probe_in1(dut_busy),
    .probe_in2(dut_done),
    .probe_in3(dut_error),
    .probe_out0(vio_loadStart),
    .probe_out1(vio_weightRowData),
    .probe_out2(vio_weightRowValid),
    .probe_out3(vio_computeStart),
    .probe_out4(vio_a)
  );

  // ------------------------------------------------------------------
  // vio_weightRowValid is a JTAG-driven level, not a pulse. The FSM's
  // weightRow.ready is held high for the entire load, so a level left high
  // would be consumed as a fresh row every fabric cycle. Edge-detect it
  // into a single-cycle Decoupled `valid` beat -- one row per high->low
  // toggle. loadStart / computeStart don't need this: VmmController
  // rising-edge-detects those two internally.
  // ------------------------------------------------------------------
  reg vio_weightRowValid_d;
  always @(posedge clk) begin
    if (reset) vio_weightRowValid_d <= 1'b0;
    else       vio_weightRowValid_d <= vio_weightRowValid;
  end
  wire weightRow_valid_pulse = vio_weightRowValid & ~vio_weightRowValid_d;

  // ------------------------------------------------------------------
  // DUT: VmmController (contains Vmm + MacBank)
  // ------------------------------------------------------------------
  VmmController dut (
    .clock(clk),
    .reset(reset),

    .io_loadStart(vio_loadStart),

    .io_weightRow_ready(dut_weightRowReady),
    .io_weightRow_valid(weightRow_valid_pulse),
    .io_weightRow_bits_0(vio_weightRowData[3:0]),
    .io_weightRow_bits_1(vio_weightRowData[7:4]),
    .io_weightRow_bits_2(vio_weightRowData[11:8]),
    .io_weightRow_bits_3(vio_weightRowData[15:12]),
    .io_weightRow_bits_4(vio_weightRowData[19:16]),
    .io_weightRow_bits_5(vio_weightRowData[23:20]),
    .io_weightRow_bits_6(vio_weightRowData[27:24]),
    .io_weightRow_bits_7(vio_weightRowData[31:28]),
    .io_weightRow_bits_8(vio_weightRowData[35:32]),
    .io_weightRow_bits_9(vio_weightRowData[39:36]),
    .io_weightRow_bits_10(vio_weightRowData[43:40]),
    .io_weightRow_bits_11(vio_weightRowData[47:44]),
    .io_weightRow_bits_12(vio_weightRowData[51:48]),
    .io_weightRow_bits_13(vio_weightRowData[55:52]),
    .io_weightRow_bits_14(vio_weightRowData[59:56]),
    .io_weightRow_bits_15(vio_weightRowData[63:60]),
    .io_weightRow_bits_16(vio_weightRowData[67:64]),
    .io_weightRow_bits_17(vio_weightRowData[71:68]),
    .io_weightRow_bits_18(vio_weightRowData[75:72]),
    .io_weightRow_bits_19(vio_weightRowData[79:76]),
    .io_weightRow_bits_20(vio_weightRowData[83:80]),
    .io_weightRow_bits_21(vio_weightRowData[87:84]),
    .io_weightRow_bits_22(vio_weightRowData[91:88]),
    .io_weightRow_bits_23(vio_weightRowData[95:92]),
    .io_weightRow_bits_24(vio_weightRowData[99:96]),
    .io_weightRow_bits_25(vio_weightRowData[103:100]),
    .io_weightRow_bits_26(vio_weightRowData[107:104]),
    .io_weightRow_bits_27(vio_weightRowData[111:108]),
    .io_weightRow_bits_28(vio_weightRowData[115:112]),
    .io_weightRow_bits_29(vio_weightRowData[119:116]),
    .io_weightRow_bits_30(vio_weightRowData[123:120]),
    .io_weightRow_bits_31(vio_weightRowData[127:124]),

    .io_computeStart(vio_computeStart),
    .io_a_0(vio_a[3:0]),
    .io_a_1(vio_a[7:4]),
    .io_a_2(vio_a[11:8]),
    .io_a_3(vio_a[15:12]),
    .io_a_4(vio_a[19:16]),
    .io_a_5(vio_a[23:20]),
    .io_a_6(vio_a[27:24]),
    .io_a_7(vio_a[31:28]),
    .io_a_8(vio_a[35:32]),
    .io_a_9(vio_a[39:36]),
    .io_a_10(vio_a[43:40]),
    .io_a_11(vio_a[47:44]),
    .io_a_12(vio_a[51:48]),
    .io_a_13(vio_a[55:52]),
    .io_a_14(vio_a[59:56]),
    .io_a_15(vio_a[63:60]),
    .io_a_16(vio_a[67:64]),
    .io_a_17(vio_a[71:68]),
    .io_a_18(vio_a[75:72]),
    .io_a_19(vio_a[79:76]),
    .io_a_20(vio_a[83:80]),
    .io_a_21(vio_a[87:84]),
    .io_a_22(vio_a[91:88]),
    .io_a_23(vio_a[95:92]),
    .io_a_24(vio_a[99:96]),
    .io_a_25(vio_a[103:100]),
    .io_a_26(vio_a[107:104]),
    .io_a_27(vio_a[111:108]),
    .io_a_28(vio_a[115:112]),
    .io_a_29(vio_a[119:116]),
    .io_a_30(vio_a[123:120]),
    .io_a_31(vio_a[127:124]),

    .io_busy(dut_busy),
    .io_done(dut_done),
    .io_error(dut_error),
    .io_y_0(dut_y[12:0]),
    .io_y_1(dut_y[25:13]),
    .io_y_2(dut_y[38:26]),
    .io_y_3(dut_y[51:39]),
    .io_y_4(dut_y[64:52]),
    .io_y_5(dut_y[77:65]),
    .io_y_6(dut_y[90:78]),
    .io_y_7(dut_y[103:91]),
    .io_y_8(dut_y[116:104]),
    .io_y_9(dut_y[129:117]),
    .io_y_10(dut_y[142:130]),
    .io_y_11(dut_y[155:143]),
    .io_y_12(dut_y[168:156]),
    .io_y_13(dut_y[181:169]),
    .io_y_14(dut_y[194:182]),
    .io_y_15(dut_y[207:195]),
    .io_y_16(dut_y[220:208]),
    .io_y_17(dut_y[233:221]),
    .io_y_18(dut_y[246:234]),
    .io_y_19(dut_y[259:247]),
    .io_y_20(dut_y[272:260]),
    .io_y_21(dut_y[285:273]),
    .io_y_22(dut_y[298:286]),
    .io_y_23(dut_y[311:299]),
    .io_y_24(dut_y[324:312]),
    .io_y_25(dut_y[337:325]),
    .io_y_26(dut_y[350:338]),
    .io_y_27(dut_y[363:351]),
    .io_y_28(dut_y[376:364]),
    .io_y_29(dut_y[389:377]),
    .io_y_30(dut_y[402:390]),
    .io_y_31(dut_y[415:403])
  );

  // ------------------------------------------------------------------
  // ILA: full-width capture, triggerable on `done` so a single-shot
  // arm+trigger in the Hardware Manager lands right on the settled result.
  // probe2..4 are along only to correlate a capture against FSM state.
  // ------------------------------------------------------------------
  ila_0 ila_inst (
    .clk(clk),
    .probe0(dut_done),
    .probe1(dut_y),
    .probe2(dut_busy),
    .probe3(dut_error),
    .probe4(vio_computeStart)
  );

endmodule
