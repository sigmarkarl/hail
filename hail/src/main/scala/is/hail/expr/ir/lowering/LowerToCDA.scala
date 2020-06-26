package is.hail.expr.ir.lowering

import is.hail.expr.ir.{BlockMatrixIR, Copy, ExecuteContext, IR, MatrixIR, Pretty, Requiredness, RequirednessAnalysis, TableIR}

object LowerToCDA {

  def apply(ir: IR, typesToLower: DArrayLowering.Type, ctx: ExecuteContext): IR = {
    val r = Requiredness(ir, ctx)
    lower(ir, typesToLower, ctx, r)
  }

  def lower(ir: IR, typesToLower: DArrayLowering.Type, ctx: ExecuteContext, r: RequirednessAnalysis): IR = ir match {
    case node if node.children.forall(_.isInstanceOf[IR]) =>
      Copy(node, ir.children.map { case c: IR => lower(c, typesToLower, ctx, r) })

    case node if node.children.exists(n => n.isInstanceOf[TableIR]) && node.children.forall(n => n.isInstanceOf[TableIR] || n.isInstanceOf[IR]) =>
      LowerTableIR(ir, typesToLower, ctx, r)

    case node if node.children.exists(n => n.isInstanceOf[BlockMatrixIR]) && node.children.forall(n => n.isInstanceOf[BlockMatrixIR] || n.isInstanceOf[IR]) =>
      LowerBlockMatrixIR(ir, typesToLower, ctx, r)

    case node if node.children.exists(_.isInstanceOf[MatrixIR]) =>
      throw new LowererUnsupportedOperation(s"MatrixIR nodes must be lowered to TableIR nodes separately: \n${ Pretty(node) }")

    case node =>
      throw new LowererUnsupportedOperation(s"Cannot lower: \n${ Pretty(node) }")
  }
}

object DArrayLowering extends Enumeration {
  type Type = Value
  val All, TableOnly, BMOnly = Value
  def lowerTable(t: Type): Boolean = t == All || t == TableOnly
  def lowerBM(t: Type): Boolean = t == All || t == BMOnly
}
