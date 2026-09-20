`timescale 1ns / 1ps
//
// Self-checking testbench for VmmController (the sequencing FSM around Vmm)
// at the full-target config (dim=32, inWidth=4, accWidth=13), for Vivado's
// xsim ("Run Simulation"). Plain Verilog-2001 -- no SystemVerilog -- so it
// drops into a simulation source set alongside generated/VmmController.v.
//
// This drives the high-level handshake:
//   1. pulse io_loadStart (edge-detected inside the FSM)
//   2. stream 32 weight rows through the Decoupled weightRow port
//      (weightRow.ready is held high through the load, so one row/cycle)
//   3. pulse io_computeStart with the activation vector on io_a
//   4. wait for io_done, then check io_y == a^T * W and that it holds
// and checks io_error stays low. The core-only equivalent is sim/tb_vmm.v.
//
// Needs generated/VmmController.v (which contains VmmController + Vmm +
// MacBank). DUT Vec ports are flattened per-element by the Chisel/FIRRTL
// backend (io_weightRow_bits_0..31, io_a_0..31, io_y_0..31); this bench
// packs them into flat regs/wires and connects nibble/lane slices.
//
module tb_vmm_controller;

  localparam integer DIM  = 32;
  localparam integer INW  = 4;
  localparam integer ACCW = 13;

  reg clock;
  reg reset;

  reg                  loadStart;
  reg                  weightRowValid;
  wire                 weightRowReady;
  reg  [DIM*INW-1:0]   weightRowData;   // lane i = [i*INW +: INW]

  reg                  computeStart;
  reg  [DIM*INW-1:0]   aVec;

  wire                 busy;
  wire                 done;
  wire                 errorFlag;
  wire [DIM*ACCW-1:0]  yVec;            // lane i = [i*ACCW +: ACCW]

  // Golden model (host side)
  reg signed [INW-1:0]  wGold [0:DIM-1][0:DIM-1]; // wGold[row][col]
  reg signed [INW-1:0]  aGold [0:DIM-1];
  reg signed [ACCW-1:0] expected [0:DIM-1];
  integer r, c, errors;

  // ------------------------------------------------------------------
  // DUT
  // ------------------------------------------------------------------
  VmmController dut (
    .clock(clock),
    .reset(reset),
    .io_loadStart(loadStart),
    .io_weightRow_ready(weightRowReady),
    .io_weightRow_valid(weightRowValid),
    .io_weightRow_bits_0(weightRowData[  3:  0]),
    .io_weightRow_bits_1(weightRowData[  7:  4]),
    .io_weightRow_bits_2(weightRowData[ 11:  8]),
    .io_weightRow_bits_3(weightRowData[ 15: 12]),
    .io_weightRow_bits_4(weightRowData[ 19: 16]),
    .io_weightRow_bits_5(weightRowData[ 23: 20]),
    .io_weightRow_bits_6(weightRowData[ 27: 24]),
    .io_weightRow_bits_7(weightRowData[ 31: 28]),
    .io_weightRow_bits_8(weightRowData[ 35: 32]),
    .io_weightRow_bits_9(weightRowData[ 39: 36]),
    .io_weightRow_bits_10(weightRowData[ 43: 40]),
    .io_weightRow_bits_11(weightRowData[ 47: 44]),
    .io_weightRow_bits_12(weightRowData[ 51: 48]),
    .io_weightRow_bits_13(weightRowData[ 55: 52]),
    .io_weightRow_bits_14(weightRowData[ 59: 56]),
    .io_weightRow_bits_15(weightRowData[ 63: 60]),
    .io_weightRow_bits_16(weightRowData[ 67: 64]),
    .io_weightRow_bits_17(weightRowData[ 71: 68]),
    .io_weightRow_bits_18(weightRowData[ 75: 72]),
    .io_weightRow_bits_19(weightRowData[ 79: 76]),
    .io_weightRow_bits_20(weightRowData[ 83: 80]),
    .io_weightRow_bits_21(weightRowData[ 87: 84]),
    .io_weightRow_bits_22(weightRowData[ 91: 88]),
    .io_weightRow_bits_23(weightRowData[ 95: 92]),
    .io_weightRow_bits_24(weightRowData[ 99: 96]),
    .io_weightRow_bits_25(weightRowData[103:100]),
    .io_weightRow_bits_26(weightRowData[107:104]),
    .io_weightRow_bits_27(weightRowData[111:108]),
    .io_weightRow_bits_28(weightRowData[115:112]),
    .io_weightRow_bits_29(weightRowData[119:116]),
    .io_weightRow_bits_30(weightRowData[123:120]),
    .io_weightRow_bits_31(weightRowData[127:124]),
    .io_computeStart(computeStart),
    .io_a_0(aVec[  3:  0]),
    .io_a_1(aVec[  7:  4]),
    .io_a_2(aVec[ 11:  8]),
    .io_a_3(aVec[ 15: 12]),
    .io_a_4(aVec[ 19: 16]),
    .io_a_5(aVec[ 23: 20]),
    .io_a_6(aVec[ 27: 24]),
    .io_a_7(aVec[ 31: 28]),
    .io_a_8(aVec[ 35: 32]),
    .io_a_9(aVec[ 39: 36]),
    .io_a_10(aVec[ 43: 40]),
    .io_a_11(aVec[ 47: 44]),
    .io_a_12(aVec[ 51: 48]),
    .io_a_13(aVec[ 55: 52]),
    .io_a_14(aVec[ 59: 56]),
    .io_a_15(aVec[ 63: 60]),
    .io_a_16(aVec[ 67: 64]),
    .io_a_17(aVec[ 71: 68]),
    .io_a_18(aVec[ 75: 72]),
    .io_a_19(aVec[ 79: 76]),
    .io_a_20(aVec[ 83: 80]),
    .io_a_21(aVec[ 87: 84]),
    .io_a_22(aVec[ 91: 88]),
    .io_a_23(aVec[ 95: 92]),
    .io_a_24(aVec[ 99: 96]),
    .io_a_25(aVec[103:100]),
    .io_a_26(aVec[107:104]),
    .io_a_27(aVec[111:108]),
    .io_a_28(aVec[115:112]),
    .io_a_29(aVec[119:116]),
    .io_a_30(aVec[123:120]),
    .io_a_31(aVec[127:124]),
    .io_busy(busy),
    .io_done(done),
    .io_error(errorFlag),
    .io_y_0(yVec[ 12:  0]),
    .io_y_1(yVec[ 25: 13]),
    .io_y_2(yVec[ 38: 26]),
    .io_y_3(yVec[ 51: 39]),
    .io_y_4(yVec[ 64: 52]),
    .io_y_5(yVec[ 77: 65]),
    .io_y_6(yVec[ 90: 78]),
    .io_y_7(yVec[103: 91]),
    .io_y_8(yVec[116:104]),
    .io_y_9(yVec[129:117]),
    .io_y_10(yVec[142:130]),
    .io_y_11(yVec[155:143]),
    .io_y_12(yVec[168:156]),
    .io_y_13(yVec[181:169]),
    .io_y_14(yVec[194:182]),
    .io_y_15(yVec[207:195]),
    .io_y_16(yVec[220:208]),
    .io_y_17(yVec[233:221]),
    .io_y_18(yVec[246:234]),
    .io_y_19(yVec[259:247]),
    .io_y_20(yVec[272:260]),
    .io_y_21(yVec[285:273]),
    .io_y_22(yVec[298:286]),
    .io_y_23(yVec[311:299]),
    .io_y_24(yVec[324:312]),
    .io_y_25(yVec[337:325]),
    .io_y_26(yVec[350:338]),
    .io_y_27(yVec[363:351]),
    .io_y_28(yVec[376:364]),
    .io_y_29(yVec[389:377]),
    .io_y_30(yVec[402:390]),
    .io_y_31(yVec[415:403])
  );

  // ------------------------------------------------------------------
  // Clock: 100 MHz
  // ------------------------------------------------------------------
  initial clock = 1'b0;
  always #5 clock = ~clock;

  initial begin
    $dumpfile("tb_vmm_controller.vcd");
    $dumpvars(0, tb_vmm_controller);
  end

  // ------------------------------------------------------------------
  // Load the full weight matrix through the controller handshake.
  // All stimulus changes on the falling edge (as in tb_vmm.v) so every
  // value the DUT samples at a posedge has been stable for a half cycle.
  // ------------------------------------------------------------------
  task load_matrix;
    integer rr, cc;
    begin
      // One-cycle loadStart pulse -- the FSM edge-detects it.
      @(negedge clock); loadStart = 1'b1;
      @(negedge clock); loadStart = 1'b0;
      // Stream the rows. weightRow.ready is held high for the whole load,
      // so holding valid high feeds one row per cycle.
      for (rr = 0; rr < DIM; rr = rr + 1) begin
        for (cc = 0; cc < DIM; cc = cc + 1)
          weightRowData[cc*INW +: INW] = wGold[rr][cc];
        weightRowValid = 1'b1;
        @(negedge clock);
      end
      weightRowValid = 1'b0;
    end
  endtask

  // ------------------------------------------------------------------
  // Stimulus + golden-model self-check
  // ------------------------------------------------------------------
  initial begin
    errors = 0;

    // Weight matrix + activation vector: same deterministic INT4 patterns
    // as sim/tb_vmm.v, both spanning [-4, 4].
    for (r = 0; r < DIM; r = r + 1)
      for (c = 0; c < DIM; c = c + 1)
        wGold[r][c] = ((r*7 + c*3 + 5) % 9) - 4;
    for (r = 0; r < DIM; r = r + 1)
      aGold[r] = ((r*13 + 4) % 9) - 4;

    for (c = 0; c < DIM; c = c + 1) begin
      expected[c] = 0;
      for (r = 0; r < DIM; r = r + 1)
        expected[c] = expected[c] + aGold[r] * wGold[r][c];
    end

    // --- Reset
    reset          = 1'b1;
    loadStart      = 1'b0;
    weightRowValid = 1'b0;
    weightRowData  = {DIM*INW{1'b0}};
    computeStart   = 1'b0;
    aVec           = {DIM*INW{1'b0}};
    repeat (3) @(negedge clock);
    reset = 1'b0;
    @(negedge clock);

    // --- Load W through the controller
    load_matrix;

    // Controller should be back to idle, no error.
    @(negedge clock);
    if (busy !== 1'b0) begin
      $display("FAIL: busy still high after the weight load"); errors = errors + 1;
    end
    if (errorFlag !== 1'b0) begin
      $display("FAIL: error asserted after a clean weight load"); errors = errors + 1;
    end

    // --- Present the activation vector, pulse computeStart
    for (c = 0; c < DIM; c = c + 1)
      aVec[c*INW +: INW] = aGold[c];
    @(negedge clock); computeStart = 1'b1;
    @(negedge clock); computeStart = 1'b0;

    // --- Wait for the done pulse, then check y
    @(posedge done);
    #1;
    for (c = 0; c < DIM; c = c + 1) begin
      if ($signed(yVec[c*ACCW +: ACCW]) !== expected[c]) begin
        $display("FAIL: y[%0d] = %0d, expected %0d",
                 c, $signed(yVec[c*ACCW +: ACCW]), expected[c]);
        errors = errors + 1;
      end
    end
    if (errorFlag !== 1'b0) begin
      $display("FAIL: error asserted on a clean compute pass"); errors = errors + 1;
    end

    // --- Result must hold across idle cycles (no re-accumulation)
    repeat (4) @(posedge clock);
    #1;
    for (c = 0; c < DIM; c = c + 1) begin
      if ($signed(yVec[c*ACCW +: ACCW]) !== expected[c]) begin
        $display("FAIL: y[%0d] drifted to %0d after idle cycles, expected %0d held",
                 c, $signed(yVec[c*ACCW +: ACCW]), expected[c]);
        errors = errors + 1;
      end
    end

    if (errors == 0)
      $display("PASS: VmmController sequenced the core to y=a^T*W and held it (dim=%0d, inWidth=%0d, accWidth=%0d)",
               DIM, INW, ACCW);
    else
      $display("FAIL: %0d mismatch(es) -- see above", errors);

    $finish;
  end

  // Safety timeout in case done never comes
  initial begin
    #200000;
    $display("FAIL: testbench timed out waiting for done");
    $finish;
  end

endmodule
