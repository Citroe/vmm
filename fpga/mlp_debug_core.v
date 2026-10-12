`timescale 1ns / 1ps
//
// Self-contained MLP bring-up core: CimSystem (TCDM + CIM accelerator + array
// + the temporary MLP scheduler) plus a small stand-in for the host -- a ROM
// with the 16 golden test images, an FSM that copies one into the TCDM
// through the system's host port and starts a run, and a checker that reads
// the logits back out of the TCDM and compares them and the class against
// the golden values. No vendor IP in here, so it simulates as-is
// (sim/tb_mlp_debug.v); fpga/mlp_debug.v wraps it with the VIO/ILA.
//
// The weights and biases are not loaded by this harness: they are the
// initial contents of the TCDM banks (tcdm_bank{0..3}.mem, read by
// tcdm_bank.v). All memory images come from fpga/gen_mlp_mem.py.
//
// Behaviour:
//   - `ready` is high whenever the harness is idle (straight out of reset).
//   - A rising edge on `run` (while `ready`) writes image `img_sel` to the
//     TCDM, runs one inference, reads the ten logits back and latches
//     cls / logits / match / cycles.
//   - A rising edge on `run_all` does the same for images 0..15 in turn.
//     Every run sets that image's bit in done_mask, and in pass_mask if the
//     class and all ten logits equal the golden values (run_all clears both
//     masks first). All 16 passing reads pass_mask == 16'hFFFF.
//   - `error` is sticky until reset: the system raised its own error, or
//     went idle without `done`.
//
module mlp_debug_core #(
  parameter IMAGE_MEM  = "mlp_images.mem",
  parameter EXPECT_MEM = "mlp_expected.mem"
) (
  input  wire         clk,
  input  wire         reset,

  input  wire         run,
  input  wire         run_all,
  input  wire [3:0]   img_sel,

  output wire         ready,         // idle, waiting for run / run_all
  output reg          result_valid,  // cls/logits/match/cycles hold a result
  output reg          error,
  output reg  [3:0]   cur_img,       // image of the current / last run
  output reg  [3:0]   cls,
  output reg  [139:0] logits,        // logit i at [14*i+13 : 14*i]
  output reg          match,         // last run equals golden (class + logits)
  output wire [3:0]   exp_cls,       // golden predicted class for cur_img
  output wire [3:0]   exp_label,     // true MNIST label for cur_img
  output reg  [15:0]  pass_mask,
  output reg  [15:0]  done_mask,
  output reg  [15:0]  cycles,        // start pulse -> done, last run

  // raw system outputs, for the ILA
  output wire         eng_busy,
  output wire         eng_done,
  output wire         eng_error,
  output wire [3:0]   eng_cls,
  output wire [139:0] eng_logits     // = logits: read back after `done`
);

  // TCDM layout, see vmm.cim.CimMap
  localparam [14:0] IMG_BASE    = 15'h0000;
  localparam [14:0] LOGITS_BASE = 15'h0240;
  localparam IMG_WORDS = 100;
  localparam N_LOGITS  = 10;

  // ------------------------------------------------------------------
  // ROMs (the image ROM is synchronous read -> block RAM)
  // ------------------------------------------------------------------
  (* rom_style = "block" *) reg [31:0] image_rom [0:2047];
  reg [147:0] expect_rom [0:15];

  initial begin
    $readmemh(IMAGE_MEM,  image_rom);
    $readmemh(EXPECT_MEM, expect_rom);
  end

  // ------------------------------------------------------------------
  // Host FSM
  //   I_WAIT / I_LOAD  pointer to 0, image ROM reading word 0
  //   I_COPY           write the image's 100 words to the TCDM
  //   GO               one-cycle start pulse
  //   RUN              wait for done
  //   L_READ           read the 10 logits back from the TCDM
  //   CHECK            compare with golden, update the masks
  // ------------------------------------------------------------------
  localparam [2:0]
    S_READY = 3'd0, S_I_WAIT = 3'd1, S_I_LOAD = 3'd2, S_I_COPY = 3'd3,
    S_GO    = 3'd4, S_RUN    = 3'd5, S_L_READ = 3'd6, S_CHECK  = 3'd7;

  reg [2:0] state;
  reg [6:0] ptr;        // word being requested
  reg [3:0] rsp;        // logit being received
  reg       all_mode;

  reg run_d, run_all_d;
  always @(posedge clk) begin
    if (reset) begin run_d <= 1'b0; run_all_d <= 1'b0; end
    else       begin run_d <= run;  run_all_d <= run_all; end
  end
  wire run_pulse     = run & ~run_d;
  wire run_all_pulse = run_all & ~run_all_d;

  wire        host_gnt, host_rvalid;
  wire [31:0] host_rdata;
  wire        host_we   = (state == S_I_COPY);
  wire        host_req  = host_we | (state == S_L_READ && ptr != N_LOGITS);
  wire [14:0] host_addr = (host_we ? IMG_BASE : LOGITS_BASE) + {6'd0, ptr, 2'b00};
  wire        fire      = host_req & host_gnt;

  // Read the *next* word whenever one is accepted, so the ROM output always
  // holds the word the pointer names.
  wire [6:0] rd_ptr = fire ? ptr + 7'd1 : ptr;
  reg [31:0]  i_q;
  reg [147:0] exp_q;
  always @(posedge clk) begin
    i_q   <= image_rom[{cur_img, rd_ptr}];
    exp_q <= expect_rom[cur_img];
  end
  assign exp_label = exp_q[147:144];
  assign exp_cls   = exp_q[143:140];

  wire result_ok = (cls == exp_q[143:140]) && (logits == exp_q[139:0]);

  assign ready      = (state == S_READY);
  assign eng_logits = logits;

  always @(posedge clk) begin
    if (reset) begin
      state        <= S_READY;
      ptr          <= 7'd0;
      rsp          <= 4'd0;
      all_mode     <= 1'b0;
      result_valid <= 1'b0;
      error        <= 1'b0;
      cur_img      <= 4'd0;
      cls          <= 4'd0;
      logits       <= 140'd0;
      match        <= 1'b0;
      pass_mask    <= 16'd0;
      done_mask    <= 16'd0;
      cycles       <= 16'd0;
    end else begin
      if (fire) ptr <= ptr + 7'd1;
      if (eng_error) error <= 1'b1;

      case (state)
        S_READY:
          if (run_all_pulse) begin
            all_mode     <= 1'b1;
            cur_img      <= 4'd0;
            pass_mask    <= 16'd0;
            done_mask    <= 16'd0;
            result_valid <= 1'b0;
            state        <= S_I_WAIT;
          end else if (run_pulse) begin
            all_mode     <= 1'b0;
            cur_img      <= img_sel;
            result_valid <= 1'b0;
            state        <= S_I_WAIT;
          end

        S_I_WAIT: begin ptr <= 7'd0; if (!eng_busy) state <= S_I_LOAD; end
        S_I_LOAD: state <= S_I_COPY;
        S_I_COPY: if (fire && ptr == IMG_WORDS - 1) state <= S_GO;

        S_GO: begin cycles <= 16'd0; state <= S_RUN; end
        S_RUN: begin
          cycles <= cycles + 16'd1;
          if (eng_done) begin
            cls   <= eng_cls;
            ptr   <= 7'd0;
            rsp   <= 4'd0;
            state <= S_L_READ;
          end else if (!eng_busy) begin
            // the system dropped back to idle without finishing
            error <= 1'b1;
            state <= S_READY;
          end
        end

        S_L_READ:
          if (host_rvalid) begin
            // stored sign-extended to 32 bits; the golden word keeps 14
            logits[14 * rsp +: 14] <= host_rdata[13:0];
            rsp <= rsp + 4'd1;
            if (rsp == N_LOGITS - 1) state <= S_CHECK;
          end

        S_CHECK: begin
          match              <= result_ok;
          pass_mask[cur_img] <= result_ok;
          done_mask[cur_img] <= 1'b1;
          result_valid       <= 1'b1;
          if (all_mode && cur_img != 4'd15) begin
            cur_img <= cur_img + 4'd1;
            state   <= S_I_WAIT;
          end else begin
            state <= S_READY;
          end
        end

        default: state <= S_READY;
      endcase
    end
  end

  // ------------------------------------------------------------------
  // DUT
  // ------------------------------------------------------------------
  CimSystem dut (
    .clock(clk),
    .reset(reset),

    .io_host_req(host_req),
    .io_host_addr(host_addr),
    .io_host_we(host_we),
    .io_host_be(4'hF),
    .io_host_wdata(i_q),
    .io_host_gnt(host_gnt),
    .io_host_rvalid(host_rvalid),
    .io_host_rdata(host_rdata),

    .io_start(state == S_GO),
    .io_busy(eng_busy),
    .io_done(eng_done),
    .io_error(eng_error),
    .io_cls(eng_cls)
  );

endmodule
