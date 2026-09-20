package vmm

import chisel3.util.log2Ceil

/** Sizing for a `dim` x `dim` fully-spatial, LUT-based weight-stationary
  * VMM/CiM array.
  *
  * accWidth is chosen so a single column accumulator can never overflow,
  * even in the worst case where every one of the `dim` products is
  * simultaneously at its extreme value (full-scale carry):
  *   - a signed inWidth x inWidth product needs 2*inWidth bits (e.g.
  *     min*min = -8*-8 = 64 at inWidth=4, which does not fit in 7 signed
  *     bits)
  *   - summing `dim` such products needs log2Ceil(dim) additional guard bits
  * For dim=32, inWidth=4: 8 + 5 = 13 bits.
  *
  * macLatency is the fixed pipeline depth of the spatial multiply-array +
  * adder-tree datapath (see MacBank): one cycle to register the products,
  * plus one register stage per adder-tree level.
  */
case class VmmParams(
  dim: Int = 32,
  inWidth: Int = 4,
) {
  require(dim > 0 && (dim & (dim - 1)) == 0, "dim must be a power of two for exact guard-bit sizing")

  val prodWidth: Int = 2 * inWidth
  val accWidth: Int = prodWidth + log2Ceil(dim)
  val macLatency: Int = log2Ceil(dim) + 1
}
