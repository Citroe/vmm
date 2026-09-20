`timescale 1ns / 1ps
//
// Self-checking testbench for the full-target Vmm config (dim=32,
// inWidth=4, accWidth=13), targeting Vivado's xsim (behavioral simulation
// / "Run Simulation" flow). Plain Verilog-2001 -- no SystemVerilog classes,
// interfaces, or clocking blocks -- so it drops straight into a Vivado
// simulation source set alongside generated/Vmm.v.
//
// DUT ports are flattened per-Vec-element by the Chisel/FIRRTL backend
// (io_weightRowData_0..31, io_a_0..31, io_y_0..31), so this
// testbench mirrors that with unpacked arrays and connects them
// element-by-element.
//
// Protocol under test (see src/main/scala/vmm/Vmm.scala):
//   1. Load W one row/cycle via weightLoad + weightRow + weightRowData.
//   2. Present the activation vector on `a`, pulse aValid for one cycle
//      (only the rising edge matters -- only one pass may be in flight at
//      a time, see Vmm.scala/MacBank.scala).
//   3. yValid pulses (macLatency + 1) cycles later, a fixed count
//      independent of dim now that MacBank is a fully-spatial pipelined
//      array (log2Ceil(dim) + 1 = 6 cycles internal to MacBank, + 1 cycle
//      for Vmm's aReg/start latch -- see VmmParams.macLatency); y = a^T * W
//      and holds.
//
module tb_vmm;

  localparam integer DIM   = 32;
  localparam integer INW   = 4;
  localparam integer ACCW  = 13;
  localparam integer LATENCY = 7; // Vmm's aReg/start latch (1) + MacBank's macLatency = log2Ceil(dim)+1 (6)

  reg clock;
  reg reset;

  reg              weightLoad;
  reg [4:0]        weightRow;
  reg signed [3:0] weightRowData [0:31];

  reg              aValid;
  reg signed [3:0] a [0:31];

  wire             yValid;
  wire signed [12:0] y [0:31];

  // Golden model (computed on the host side of the testbench).
  reg signed [3:0]  wGold [0:31][0:31]; // wGold[row][col]
  reg signed [12:0] expected [0:31];

  integer r, c;
  integer errors;

  // ------------------------------------------------------------------
  // DUT
  // ------------------------------------------------------------------
  Vmm dut (
    .clock(clock),
    .reset(reset),
    .io_weightLoad(weightLoad),
    .io_weightRow(weightRow),
    .io_weightRowData_0(weightRowData[0]),
    .io_weightRowData_1(weightRowData[1]),
    .io_weightRowData_2(weightRowData[2]),
    .io_weightRowData_3(weightRowData[3]),
    .io_weightRowData_4(weightRowData[4]),
    .io_weightRowData_5(weightRowData[5]),
    .io_weightRowData_6(weightRowData[6]),
    .io_weightRowData_7(weightRowData[7]),
    .io_weightRowData_8(weightRowData[8]),
    .io_weightRowData_9(weightRowData[9]),
    .io_weightRowData_10(weightRowData[10]),
    .io_weightRowData_11(weightRowData[11]),
    .io_weightRowData_12(weightRowData[12]),
    .io_weightRowData_13(weightRowData[13]),
    .io_weightRowData_14(weightRowData[14]),
    .io_weightRowData_15(weightRowData[15]),
    .io_weightRowData_16(weightRowData[16]),
    .io_weightRowData_17(weightRowData[17]),
    .io_weightRowData_18(weightRowData[18]),
    .io_weightRowData_19(weightRowData[19]),
    .io_weightRowData_20(weightRowData[20]),
    .io_weightRowData_21(weightRowData[21]),
    .io_weightRowData_22(weightRowData[22]),
    .io_weightRowData_23(weightRowData[23]),
    .io_weightRowData_24(weightRowData[24]),
    .io_weightRowData_25(weightRowData[25]),
    .io_weightRowData_26(weightRowData[26]),
    .io_weightRowData_27(weightRowData[27]),
    .io_weightRowData_28(weightRowData[28]),
    .io_weightRowData_29(weightRowData[29]),
    .io_weightRowData_30(weightRowData[30]),
    .io_weightRowData_31(weightRowData[31]),
    .io_aValid(aValid),
    .io_a_0(a[0]),
    .io_a_1(a[1]),
    .io_a_2(a[2]),
    .io_a_3(a[3]),
    .io_a_4(a[4]),
    .io_a_5(a[5]),
    .io_a_6(a[6]),
    .io_a_7(a[7]),
    .io_a_8(a[8]),
    .io_a_9(a[9]),
    .io_a_10(a[10]),
    .io_a_11(a[11]),
    .io_a_12(a[12]),
    .io_a_13(a[13]),
    .io_a_14(a[14]),
    .io_a_15(a[15]),
    .io_a_16(a[16]),
    .io_a_17(a[17]),
    .io_a_18(a[18]),
    .io_a_19(a[19]),
    .io_a_20(a[20]),
    .io_a_21(a[21]),
    .io_a_22(a[22]),
    .io_a_23(a[23]),
    .io_a_24(a[24]),
    .io_a_25(a[25]),
    .io_a_26(a[26]),
    .io_a_27(a[27]),
    .io_a_28(a[28]),
    .io_a_29(a[29]),
    .io_a_30(a[30]),
    .io_a_31(a[31]),
    .io_yValid(yValid),
    .io_y_0(y[0]),
    .io_y_1(y[1]),
    .io_y_2(y[2]),
    .io_y_3(y[3]),
    .io_y_4(y[4]),
    .io_y_5(y[5]),
    .io_y_6(y[6]),
    .io_y_7(y[7]),
    .io_y_8(y[8]),
    .io_y_9(y[9]),
    .io_y_10(y[10]),
    .io_y_11(y[11]),
    .io_y_12(y[12]),
    .io_y_13(y[13]),
    .io_y_14(y[14]),
    .io_y_15(y[15]),
    .io_y_16(y[16]),
    .io_y_17(y[17]),
    .io_y_18(y[18]),
    .io_y_19(y[19]),
    .io_y_20(y[20]),
    .io_y_21(y[21]),
    .io_y_22(y[22]),
    .io_y_23(y[23]),
    .io_y_24(y[24]),
    .io_y_25(y[25]),
    .io_y_26(y[26]),
    .io_y_27(y[27]),
    .io_y_28(y[28]),
    .io_y_29(y[29]),
    .io_y_30(y[30]),
    .io_y_31(y[31])
  );

  // ------------------------------------------------------------------
  // Clock: 100 MHz (10ns period)
  // ------------------------------------------------------------------
  initial clock = 1'b0;
  always #5 clock = ~clock;

  // ------------------------------------------------------------------
  // Waveform dump (readable in Vivado's waveform viewer too)
  // ------------------------------------------------------------------
  initial begin
    $dumpfile("tb_vmm.vcd");
    $dumpvars(0, tb_vmm);
  end

  // ------------------------------------------------------------------
  // Stimulus + golden-model self-check
  // ------------------------------------------------------------------
  initial begin
    errors = 0;

    // --- Weight matrix: any full-range, deterministic INT4 pattern.
    // W[r][c] = ((r*7 + c*3 + 5) mod 9) - 4 -> spans [-4, 4], varies with
    // both row and column so every PE gets a distinct tap.
    for (r = 0; r < DIM; r = r + 1)
      for (c = 0; c < DIM; c = c + 1)
        wGold[r][c] = ((r*7 + c*3 + 5) % 9) - 4;

    // --- Activation vector: the array's native input width is 32 lanes
    // of INT4 (dim=32, inWidth=4).
    // a[i] = ((i*13 + 4) mod 9) - 4 -> spans [-4, 4].
    for (r = 0; r < DIM; r = r + 1)
      a[r] = ((r*13 + 4) % 9) - 4;

    // --- Golden model: y[c] = sum_r a[r] * W[r][c]
    for (c = 0; c < DIM; c = c + 1) begin
      expected[c] = 0;
      for (r = 0; r < DIM; r = r + 1)
        expected[c] = expected[c] + a[r] * wGold[r][c];
    end

    // All stimulus below changes on the falling edge, so every value the
    // DUT samples at a posedge has been stable for a full half-cycle --
    // driving it right after a posedge would race the DUT's own
    // posedge-triggered always blocks on that same edge (undefined
    // ordering between concurrent processes woken by the same event).

    // --- Reset
    reset      = 1'b1;
    weightLoad = 1'b0;
    weightRow  = 5'd0;
    aValid     = 1'b0;
    for (c = 0; c < DIM; c = c + 1) weightRowData[c] = 4'sd0;
    repeat (3) @(negedge clock);
    reset = 1'b0;
    @(negedge clock);

    // --- Load W, one row per cycle
    for (r = 0; r < DIM; r = r + 1) begin
      weightRow = r[4:0];
      for (c = 0; c < DIM; c = c + 1) weightRowData[c] = wGold[r][c];
      weightLoad = 1'b1;
      @(negedge clock);
    end
    weightLoad = 1'b0;

    // --- Present the activation vector, pulse aValid for one cycle
    @(negedge clock);
    aValid = 1'b1;
    @(negedge clock);
    aValid = 1'b0;

    // --- Wait for the yValid pulse (documented at LATENCY cycles), then
    // check; sampling on yValid's edge rather than a hand-counted cycle
    // tally avoids off-by-one drift if the latency ever changes.
    @(posedge yValid);
    #1;

    for (c = 0; c < DIM; c = c + 1) begin
      if (y[c] !== expected[c]) begin
        $display("FAIL: y[%0d] = %0d, expected %0d", c, y[c], expected[c]);
        errors = errors + 1;
      end
    end

    // --- Result should hold on subsequent idle cycles (no re-accumulation)
    repeat (4) @(posedge clock);
    #1;
    for (c = 0; c < DIM; c = c + 1) begin
      if (y[c] !== expected[c]) begin
        $display("FAIL: y[%0d] drifted to %0d after idle cycles, expected %0d held", c, y[c], expected[c]);
        errors = errors + 1;
      end
    end

    if (errors == 0)
      $display("PASS: all %0d outputs matched the golden model at latency %0d, y=a^T*W (dim=%0d, inWidth=%0d, accWidth=%0d)", DIM, LATENCY, DIM, INW, ACCW);
    else
      $display("FAIL: %0d mismatch(es) -- see above", errors);

    $finish;
  end

  // Safety timeout in case yValid never comes
  initial begin
    #100000;
    $display("FAIL: testbench timed out waiting for yValid");
    $finish;
  end

endmodule
