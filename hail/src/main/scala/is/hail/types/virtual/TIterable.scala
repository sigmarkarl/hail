package is.hail.types.virtual

import is.hail.types.physical.PIterable
import is.hail.utils.FastSeq

abstract class TIterable extends Type {
  def elementType: Type

  override def children = FastSeq(elementType)
}
