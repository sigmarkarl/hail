package is.hail.types.physical

import is.hail.annotations._
import is.hail.check.Gen
import is.hail.expr.ir.EmitMethodBuilder
import is.hail.types.virtual.TSet

abstract class PSet extends PContainer {
  lazy val virtualType: TSet = TSet(elementType.virtualType)

  def arrayFundamentalType: PArray = fundamentalType.asInstanceOf[PArray]

  def codeOrdering(mb: EmitMethodBuilder[_], other: PType): CodeOrdering = {
    assert(other isOfType this)
    CodeOrdering.setOrdering(this, other.asInstanceOf[PSet], mb)
  }

  override def genNonmissingValue: Gen[Annotation] = Gen.buildableOf[Set](elementType.genValue)
}
