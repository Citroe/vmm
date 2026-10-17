`timescale 1ns / 1ps
//
// Self-checking testbench for fpga/vmm_debug.v (the standalone VmmController
// JTAG harness), driven the way fpga/run_hw_test.tcl drives it on the board:
// every VIO output is a level that changes once per JTAG commit, with GAP
// fabric cycles between commits (milliseconds on hardware, far longer than
// the controller's default 1024-cycle load-stall timeout). vio_0 / ila_0 are
// replaced by stubs below that read their outputs from this testbench.
//
// Checks: the 32-row load completes with error low despite the gaps, y matches
// a^T*W for three matrices (a mixed pattern, and the two full-scale corners
// -8*-8 = +2048 and 7*-8 = -1792 per column), and each reload replaces the
// previous weights. Prints PASS:/FAIL: at $finish like the other TBs.
//
// Needs generated/VmmController.v (sbt "runMain vmm.MainController
// --target-dir generated"). With Verilator, from the repo root:
//   $ verilator --binary --timing -Wno-fatal --top-module tb_vmm_debug \
//     --Mdir <scratch dir> sim/tb_vmm_debug.v fpga/vmm_debug.v \
//     generated/VmmController.v
//   $ <scratch dir>/Vtb_vmm_debug
//
module tb_vmm_debug;

  localparam DIM  = 32;
  localparam INW  = 4;
  localparam ACCW = 13;
  localparam GAP  = 3000;  // fabric cycles between "JTAG commits"

  reg clk = 1'b0;
  always #5 clk = ~clk;
  reg resetn = 1'b0;

  // Levels the vio_0 stub presents on its probe_out ports.
  reg           loadStart = 1'b0, weightRowValid = 1'b0, computeStart = 1'b0;
  reg  [127:0]  weightRowData = 128'd0, aVec = 128'd0;

  vmm_debug dut (.clk(clk), .resetn(resetn));

  wire [415:0] y     = dut.dut_y;
  wire         busy  = dut.dut_busy;
  wire         error = dut.dut_error;
  wire         done  = dut.dut_done;

  integer wGold [0:DIM-1][0:DIM-1];
  integer aGold [0:DIM-1];
  integer expected [0:DIM-1];
  integer r, c, errors, doneCount;

  always @(posedge clk) if (done) doneCount = doneCount + 1;

  // One "JTAG commit": change happens, then GAP cycles pass.
  task commit_gap; begin repeat (GAP) @(negedge clk); end endtask

  task load_matrix;
    integer rr, cc;
    begin
      loadStart = 1'b1; commit_gap;
      loadStart = 1'b0; commit_gap;
      for (rr = 0; rr < DIM; rr = rr + 1) begin
        for (cc = 0; cc < DIM; cc = cc + 1)
          weightRowData[cc*INW +: INW] = wGold[rr][cc];
        commit_gap;
        weightRowValid = 1'b1; commit_gap;
        weightRowValid = 1'b0; commit_gap;
        if (error !== 1'b0) begin
          $display("FAIL: error raised during the load, after row %0d", rr);
          errors = errors + 1;
        end
      end
      if (busy !== 1'b0) begin
        $display("FAIL: busy still high after 32 rows"); errors = errors + 1;
      end
    end
  endtask

  task compute_and_check(input [8*24-1:0] name);
    integer cc, rr, d0;
    begin
      for (cc = 0; cc < DIM; cc = cc + 1) begin
        expected[cc] = 0;
        for (rr = 0; rr < DIM; rr = rr + 1)
          expected[cc] = expected[cc] + aGold[rr] * wGold[rr][cc];
      end
      for (cc = 0; cc < DIM; cc = cc + 1)
        aVec[cc*INW +: INW] = aGold[cc];
      commit_gap;
      d0 = doneCount;
      computeStart = 1'b1; commit_gap;
      computeStart = 1'b0; commit_gap;
      if (doneCount != d0 + 1) begin
        $display("FAIL: %0s: expected exactly one done pulse, saw %0d", name, doneCount - d0);
        errors = errors + 1;
      end
      if (error !== 1'b0) begin
        $display("FAIL: %0s: error high after compute", name); errors = errors + 1;
      end
      for (cc = 0; cc < DIM; cc = cc + 1)
        if ($signed(y[cc*ACCW +: ACCW]) !== expected[cc]) begin
          $display("FAIL: %0s: y[%0d] = %0d, expected %0d",
                   name, cc, $signed(y[cc*ACCW +: ACCW]), expected[cc]);
          errors = errors + 1;
        end
      $display("%0s: y[0..2] = %0d %0d %0d", name, $signed(y[0 +: ACCW]),
               $signed(y[ACCW +: ACCW]), $signed(y[2*ACCW +: ACCW]));
    end
  endtask

  initial begin
    errors = 0; doneCount = 0;
    repeat (5) @(negedge clk);
    resetn = 1'b1;
    repeat (5) @(negedge clk);

    // 1. Mixed pattern (same as sim/tb_vmm_controller.v).
    for (r = 0; r < DIM; r = r + 1) begin
      for (c = 0; c < DIM; c = c + 1) wGold[r][c] = ((r*7 + c*3 + 5) % 9) - 4;
      aGold[r] = ((r*13 + 4) % 9) - 4;
    end
    load_matrix;
    compute_and_check("mixed");

    // 2. Full-scale positive corner: every product -8*-8, y = +2048.
    for (r = 0; r < DIM; r = r + 1) begin
      for (c = 0; c < DIM; c = c + 1) wGold[r][c] = -8;
      aGold[r] = -8;
    end
    load_matrix;
    compute_and_check("all -8 x -8");

    // 3. Full-scale negative corner: 7*-8, y = -1792.
    for (r = 0; r < DIM; r = r + 1)
      for (c = 0; c < DIM; c = c + 1) wGold[r][c] = 7;
    load_matrix;
    compute_and_check("all 7 x -8");

    if (errors == 0)
      $display("PASS: tb_vmm_debug (3 matrices loaded at %0d-cycle JTAG gaps, y = a^T*W)", GAP);
    else
      $display("FAIL: tb_vmm_debug, %0d error(s)", errors);
    $finish;
  end

endmodule

// ---- Stubs for the Vivado debug IP that vmm_debug.v instantiates ----------

module vio_0 (
  input  wire         clk,
  input  wire         probe_in0, probe_in1, probe_in2, probe_in3,
  output wire         probe_out0,
  output wire [127:0] probe_out1,
  output wire         probe_out2,
  output wire         probe_out3,
  output wire [127:0] probe_out4
);
  assign probe_out0 = tb_vmm_debug.loadStart;
  assign probe_out1 = tb_vmm_debug.weightRowData;
  assign probe_out2 = tb_vmm_debug.weightRowValid;
  assign probe_out3 = tb_vmm_debug.computeStart;
  assign probe_out4 = tb_vmm_debug.aVec;
endmodule

module ila_0 (
  input wire         clk,
  input wire         probe0,
  input wire [415:0] probe1,
  input wire         probe2, probe3, probe4
);
endmodule
