package is.hail.expr.types.virtual

import is.hail.annotations._
import is.hail.check.Arbitrary._
import is.hail.check.Gen
import is.hail.expr.types.physical.PFloat64
import is.hail.utils._

import scala.reflect.{ClassTag, _}

case object TFloat64Optional extends TFloat64(false)
case object TFloat64Required extends TFloat64(true)

class TFloat64(override val required: Boolean) extends TNumeric {
  lazy val physicalType: PFloat64 = PFloat64(required)

  override def _toPretty = "Float64"

  override def pyString(sb: StringBuilder): Unit = {
    sb.append("float64")
  }

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Double]

  override def _showStr(a: Annotation): String = a.asInstanceOf[Double].formatted("%.02e")

  override def str(a: Annotation): String = if (a == null) "NA" else a.asInstanceOf[Double].formatted("%.5e")

  override def genNonmissingValue: Gen[Annotation] = arbitrary[Double]

  override def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double, absolute: Boolean): Boolean =
    a1 == a2 || (a1 != null && a2 != null && {
      val f1 = a1.asInstanceOf[Double]
      val f2 = a2.asInstanceOf[Double]

      val withinTol =
        if (absolute)
          math.abs(f1 - f2) <= tolerance
        else
          D_==(f1, f2, tolerance)

      f1 == f2 || withinTol || (f1.isNaN && f2.isNaN)
    })

  override def scalaClassTag: ClassTag[java.lang.Double] = classTag[java.lang.Double]

  val ordering: ExtendedOrdering =
    ExtendedOrdering.extendToNull(implicitly[Ordering[Double]])
}

object TFloat64 {
  def apply(required: Boolean = false): TFloat64 = if (required) TFloat64Required else TFloat64Optional

  def unapply(t: TFloat64): Option[Boolean] = Option(t.required)
}
