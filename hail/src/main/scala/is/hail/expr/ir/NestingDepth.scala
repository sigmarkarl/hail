package is.hail.expr.ir

case class ScopedDepth(eval: Int, agg: Int, scan: Int) {
  def incrementEval: ScopedDepth = ScopedDepth(eval + 1, agg, scan)

  def incrementAgg: ScopedDepth = ScopedDepth(eval, agg + 1, scan)

  def promoteAgg: ScopedDepth = ScopedDepth(agg, 0, 0)

  def incrementScan: ScopedDepth = ScopedDepth(eval, agg, scan + 1)

  def promoteScan: ScopedDepth = ScopedDepth(scan, 0, 0)

  def incrementScanOrAgg(isScan: Boolean): ScopedDepth = if (isScan) incrementScan else incrementAgg

  def promoteScanOrAgg(isScan: Boolean): ScopedDepth = if (isScan) promoteScan else promoteAgg
}

object NestingDepth {
  def apply(ir0: BaseIR): Memo[Int] = {

    val memo = Memo.empty[Int]

    def computeChildren(ir: BaseIR): Unit = {
      ir.children
        .iterator
        .zipWithIndex
        .foreach {
          case (child: IR, i) => computeIR(child, ScopedDepth(0, 0, 0))
          case (tir: TableIR, i) => computeTable(tir)
          case (mir: MatrixIR, i) => computeMatrix(mir)
          case (bmir: BlockMatrixIR, i) => computeBlockMatrix(bmir)
        }
    }

    def computeTable(tir: TableIR): Unit = computeChildren(tir)

    def computeMatrix(mir: MatrixIR): Unit = computeChildren(mir)

    def computeBlockMatrix(bmir: BlockMatrixIR): Unit = computeChildren(bmir)

    def computeIR(ir: IR, depth: ScopedDepth): Unit = {
      memo.bind(ir, depth.eval)
      ir match {
        case ArrayMap(a, name, body) =>
          computeIR(a, depth)
          computeIR(body, depth.incrementEval)
        case ArrayFor(a, valueName, body) =>
          computeIR(a, depth)
          computeIR(body, depth.incrementEval)
        case ArrayFlatMap(a, name, body) =>
          computeIR(a, depth)
          computeIR(body, depth.incrementEval)
        case ArrayFilter(a, name, cond) =>
          computeIR(a, depth)
          computeIR(cond, depth.incrementEval)
        case ArrayFold(a, zero, accumName, valueName, body) =>
          computeIR(a, depth)
          computeIR(zero, depth)
          computeIR(body, depth.incrementEval)
        case ArrayFold2(a, accum, _, seq, result) =>
          computeIR(a, depth)
          accum.foreach { case (_, value) => computeIR(value, depth) }
          seq.foreach(computeIR(_, depth.incrementEval))
          computeIR(result, depth)
        case ArrayScan(a, zero, accumName, valueName, body) =>
          computeIR(a, depth)
          computeIR(zero, depth)
          computeIR(body, depth.incrementEval)
        case ArrayLeftJoinDistinct(left, right, l, r, keyF, joinF) =>
          computeIR(left, depth)
          computeIR(right, depth)
          computeIR(keyF, depth.incrementEval)
          computeIR(joinF, depth.incrementEval)
        case NDArrayMap(nd, _, body) =>
          computeIR(nd, depth)
          computeIR(body, depth.incrementEval)
        case NDArrayMap2(nd1, nd2, _, _, body) =>
          computeIR(nd1, depth)
          computeIR(nd2, depth)
          computeIR(body, depth.incrementEval)
        case AggExplode(array, _, aggBody, isScan) =>
          computeIR(array, depth.promoteScanOrAgg(isScan))
          computeIR(aggBody, depth.incrementScanOrAgg(isScan))
        case AggArrayPerElement(a, _, _, aggBody, knownLength, isScan) =>
          computeIR(a, depth.promoteScanOrAgg(isScan))
          computeIR(aggBody, depth.incrementScanOrAgg(isScan))
          knownLength.foreach(computeIR(_, depth))
        case TableAggregate(child, query) =>
          computeTable(child)
          computeIR(query, ScopedDepth(0, 0, 0))
        case MatrixAggregate(child, query) =>
          computeMatrix(child)
          computeIR(query, ScopedDepth(0, 0, 0))
        case _ =>
          ir.children.iterator
            .zipWithIndex
            .foreach {
              case (child: IR, i) => if (UsesAggEnv(ir, i))
                computeIR(child, depth.promoteAgg)
              else if (UsesScanEnv(ir, i))
                computeIR(child, depth.promoteScan)
              else
                computeIR(child, depth)
              case (child: TableIR, _) => computeTable(child)
              case (child: MatrixIR, _) => computeMatrix(child)
              case (child: BlockMatrixIR, _) => computeBlockMatrix(child)
            }
      }
    }

    ir0 match {
      case ir: IR => computeIR(ir, ScopedDepth(0, 0, 0))
      case tir: TableIR => computeTable(tir)
      case mir: MatrixIR => computeMatrix(mir)
      case bmir: BlockMatrixIR => computeBlockMatrix(bmir)
    }

    memo
  }
}
