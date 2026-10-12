`timescale 1ns / 1ps
//
// Self-checking testbench for fpga/mlp_debug_core.v (the vendor-IP-free part
// of the MLP bring-up harness): runs all 16 golden images with run_all, then
// one single-image run, and checks the harness's own pass/fail flags. Prints
// PASS:/FAIL: at $finish like the other TBs.
//
// Needs generated/CimSystem.v + generated/tcdm_bank.v (sbt "runMain
// vmm.MainCim --target-dir generated") and fpga/mem/*.mem (python3
// fpga/gen_mlp_mem.py). The TCDM bank images are named without a directory
// inside CimSystem.v, so run the simulation from fpga/mem. With Verilator,
// from the repo root:
//   $ verilator --binary --timing -Wno-fatal --top-module tb_mlp_debug \
//     --Mdir <scratch dir> sim/tb_mlp_debug.v fpga/mlp_debug_core.v \
//     generated/CimSystem.v generated/tcdm_bank.v
//   $ (cd fpga/mem && <scratch dir>/Vtb_mlp_debug)
//
module tb_mlp_debug #(parameter MEM_DIR = "");

  reg clk = 1'b0;
  always #5 clk = ~clk;

  reg         reset = 1'b1;
  reg         run = 1'b0, run_all = 1'b0;
  reg  [3:0]  img_sel = 4'd0;

  wire        ready, result_valid, error, match;
  wire [3:0]  cur_img, cls, exp_cls, exp_label;
  wire [139:0] logits;
  wire [15:0] pass_mask, done_mask, cycles;

  mlp_debug_core #(
    .IMAGE_MEM ({MEM_DIR, "mlp_images.mem"}),
    .EXPECT_MEM({MEM_DIR, "mlp_expected.mem"})
  ) core (
    .clk(clk), .reset(reset),
    .run(run), .run_all(run_all), .img_sel(img_sel),
    .ready(ready), .result_valid(result_valid), .error(error),
    .cur_img(cur_img), .cls(cls), .logits(logits), .match(match),
    .exp_cls(exp_cls), .exp_label(exp_label),
    .pass_mask(pass_mask), .done_mask(done_mask), .cycles(cycles),
    .eng_busy(), .eng_done(), .eng_error(), .eng_cls(), .eng_logits()
  );

  integer errors = 0;
  integer guard;

  task wait_ready;
    begin
      guard = 0;
      @(posedge clk);
      while (!ready && guard < 200000) begin @(posedge clk); guard = guard + 1; end
      if (!ready) begin
        $display("FAIL: timed out waiting for ready");
        $finish;
      end
    end
  endtask

  // one line per finished inference, independent of the harness's checker
  always @(posedge clk) begin
    if (core.state == core.S_CHECK)
      $display("image %0d: cls=%0d exp_cls=%0d label=%0d cycles=%0d logits[0..2]=%0d %0d %0d",
               cur_img, cls, exp_cls, exp_label, cycles,
               $signed(logits[13:0]), $signed(logits[27:14]), $signed(logits[41:28]));
  end

  initial begin
    repeat (5) @(posedge clk);
    reset = 1'b0;

    wait_ready;
    if (error) begin errors = errors + 1; $display("FAIL: error set out of reset"); end

    // all 16 images; hold the strobe high for a while like a VIO level would
    @(negedge clk) run_all = 1'b1;
    repeat (20) @(posedge clk);
    @(negedge clk) run_all = 1'b0;
    wait_ready;
    if (done_mask !== 16'hFFFF) begin
      errors = errors + 1; $display("FAIL: done_mask = %h", done_mask);
    end
    if (pass_mask !== 16'hFFFF) begin
      errors = errors + 1; $display("FAIL: pass_mask = %h", pass_mask);
    end

    // single run of a chosen image
    @(negedge clk) begin img_sel = 4'd5; run = 1'b1; end
    @(negedge clk) run = 1'b0;
    wait_ready;
    if (!(result_valid && match && cur_img == 4'd5 && cls == exp_cls)) begin
      errors = errors + 1;
      $display("FAIL: single run of image 5: valid=%b match=%b cls=%0d exp=%0d",
               result_valid, match, cls, exp_cls);
    end
    if (pass_mask !== 16'hFFFF || error) begin
      errors = errors + 1; $display("FAIL: pass_mask=%h error=%b after single run", pass_mask, error);
    end

    if (errors == 0) $display("PASS: tb_mlp_debug (16/16 golden images, cycles/inference = %0d)", cycles);
    else             $display("FAIL: tb_mlp_debug, %0d error(s)", errors);
    $finish;
  end

endmodule
