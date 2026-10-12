`timescale 1ns / 1ps
//
// Hardware bring-up harness for the whole MLP (784-64-10 INT4) running on
// CimSystem -- TCDM, CIM accelerator, array and the temporary MLP scheduler --
// on the Pynq-Z2 (xc7z020clg400-1). Like vmm_debug.v there is no bus and no
// board I/O: stimulus and readback go over JTAG through a VIO, with an ILA
// triggerable on the system's `done`.
//
// Unlike vmm_debug.v nothing has to be loaded over JTAG. The weights and
// biases are the initial contents of the TCDM banks, and mlp_debug_core
// holds the 16 golden test images in a ROM (all built into the bitstream
// from fpga/mem/*.mem); it copies an image into the TCDM, runs it, reads the
// logits back and checks them against the golden class and logits. So a
// complete board test is: pulse vio_run_all, read vio pass_mask == FFFF.
//
// VIO (vio_mlp, see create_mlp_debug_ip.tcl):
//   probe_out0  1    run        rising edge: run image img_sel
//   probe_out1  1    run_all    rising edge: run images 0..15
//   probe_out2  4    img_sel
//   probe_out3  1    soft_reset level: hold the harness in reset (TCDM
//                               contents are not affected)
//   probe_in0   1    ready      idle, waiting for run / run_all
//   probe_in1   1    result_valid
//   probe_in2   1    error
//   probe_in3   1    match      last run equals golden (class + 10 logits)
//   probe_in4   4    cls        predicted class, last run
//   probe_in5   4    exp_cls    golden predicted class for cur_img
//   probe_in6   4    exp_label  true MNIST label for cur_img
//   probe_in7   4    cur_img
//   probe_in8   16   pass_mask  bit i: image i matched golden
//   probe_in9   16   done_mask  bit i: image i has been run
//   probe_in10  16   cycles     start -> done, last run (expect 8185)
//   probe_in11  140  logits     10 x INT14, logit i at [14*i+13 : 14*i]
//
// ILA (ila_mlp): probe0 done (trigger), probe1 busy, probe2 error,
// probe3 cls (4), probe4 logits (140), probe5 cur_img (4). cls is valid on
// the `done` sample; logits are read back from the TCDM over the following
// ~12 cycles, so on the `done` sample they still show the previous run.
//
// `clk`/`resetn` come from the PS7's FCLK_CLK0 / FCLK_RESET0_N (see
// build_mlp.tcl). The reset is synchronised here.
//
module mlp_debug (
  input wire clk,
  input wire resetn
);

  wire vio_run, vio_run_all, vio_soft_reset;
  wire [3:0] vio_img_sel;

  // FCLK_RESET0_N is asynchronous to FCLK_CLK0.
  (* ASYNC_REG = "TRUE" *) reg [1:0] rst_sync = 2'b11;
  always @(posedge clk or negedge resetn) begin
    if (!resetn) rst_sync <= 2'b11;
    else         rst_sync <= {rst_sync[0], 1'b0};
  end
  reg reset = 1'b1;
  always @(posedge clk) reset <= rst_sync[1] | vio_soft_reset;

  wire         core_ready, core_result_valid, core_error, core_match;
  wire [3:0]   core_cur_img, core_cls, core_exp_cls, core_exp_label;
  wire [139:0] core_logits;
  wire [15:0]  core_pass_mask, core_done_mask, core_cycles;

  wire         eng_busy, eng_done, eng_error;
  wire [3:0]   eng_cls;
  wire [139:0] eng_logits;

  mlp_debug_core core (
    .clk(clk),
    .reset(reset),
    .run(vio_run),
    .run_all(vio_run_all),
    .img_sel(vio_img_sel),
    .ready(core_ready),
    .result_valid(core_result_valid),
    .error(core_error),
    .cur_img(core_cur_img),
    .cls(core_cls),
    .logits(core_logits),
    .match(core_match),
    .exp_cls(core_exp_cls),
    .exp_label(core_exp_label),
    .pass_mask(core_pass_mask),
    .done_mask(core_done_mask),
    .cycles(core_cycles),
    .eng_busy(eng_busy),
    .eng_done(eng_done),
    .eng_error(eng_error),
    .eng_cls(eng_cls),
    .eng_logits(eng_logits)
  );

  vio_mlp vio_inst (
    .clk(clk),
    .probe_in0(core_ready),
    .probe_in1(core_result_valid),
    .probe_in2(core_error),
    .probe_in3(core_match),
    .probe_in4(core_cls),
    .probe_in5(core_exp_cls),
    .probe_in6(core_exp_label),
    .probe_in7(core_cur_img),
    .probe_in8(core_pass_mask),
    .probe_in9(core_done_mask),
    .probe_in10(core_cycles),
    .probe_in11(core_logits),
    .probe_out0(vio_run),
    .probe_out1(vio_run_all),
    .probe_out2(vio_img_sel),
    .probe_out3(vio_soft_reset)
  );

  ila_mlp ila_inst (
    .clk(clk),
    .probe0(eng_done),
    .probe1(eng_busy),
    .probe2(eng_error),
    .probe3(eng_cls),
    .probe4(eng_logits),
    .probe5(core_cur_img)
  );

endmodule
