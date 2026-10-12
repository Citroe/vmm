`timescale 1ns / 1ps
//
// One TCDM bank: 2^AW x 32-bit single-port RAM, synchronous read, per-byte
// write enables. Written to Xilinx's byte-write-enable inference template so
// it maps to block RAM (2 RAMB36 at the default 2048 words = 8 kB).
//
// INIT_FILE, if not empty, is a $readmemh image (one 32-bit word per line);
// otherwise the bank powers up as zeros. Instantiated from Chisel as the
// black box vmm.cim.TcdmBank (see Tcdm.scala).
//
module tcdm_bank #(
  parameter INIT_FILE = "",
  parameter AW        = 11
) (
  input  wire          clk,
  input  wire          en,
  input  wire [3:0]    we,
  input  wire [AW-1:0] addr,
  input  wire [31:0]   wdata,
  output reg  [31:0]   rdata
);

  (* ram_style = "block" *) reg [31:0] mem [0:(1 << AW) - 1];

  integer k;
  initial begin
    for (k = 0; k < (1 << AW); k = k + 1) mem[k] = 32'd0;
    if (INIT_FILE != "") $readmemh(INIT_FILE, mem);
    rdata = 32'd0;
  end

  genvar b;
  generate
    for (b = 0; b < 4; b = b + 1) begin : byte_write
      always @(posedge clk)
        if (en && we[b]) mem[addr][8 * b +: 8] <= wdata[8 * b +: 8];
    end
  endgenerate

  always @(posedge clk)
    if (en) rdata <= mem[addr];

endmodule
