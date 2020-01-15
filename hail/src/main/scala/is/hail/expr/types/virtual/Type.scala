package is.hail.expr.types.virtual

import is.hail.annotations._
import is.hail.check.{Arbitrary, Gen}
import is.hail.expr.ir.IRParser
import is.hail.expr.types._
import is.hail.expr.types.physical.PType
import is.hail.expr.{JSONAnnotationImpex, SparkAnnotationImpex}
import is.hail.utils
import is.hail.utils._
import is.hail.variant.ReferenceGenome
import org.apache.spark.sql.types.DataType
import org.json4s.JsonAST.JString
import org.json4s.{CustomSerializer, JValue}

import scala.reflect.ClassTag

class TypeSerializer extends CustomSerializer[Type](format => (
  { case JString(s) => IRParser.parseType(s) },
  { case t: Type => JString(t.parsableString()) }))

object Type {
  def genScalar(required: Boolean): Gen[Type] =
    Gen.oneOf(TBoolean(required), TInt32(required), TInt64(required), TFloat32(required),
      TFloat64(required), TString(required), TCall(required))

  val genOptionalScalar: Gen[Type] = genScalar(false)

  val genRequiredScalar: Gen[Type] = genScalar(true)

  def genComplexType(required: Boolean): Gen[ComplexType] = {
    val rgDependents = ReferenceGenome.references.values.toArray.map(rg =>
      TLocus(rg, required))
    val others = Array(TCall(required))
    Gen.oneOfSeq(rgDependents ++ others)
  }

  def genFields(required: Boolean, genFieldType: Gen[Type]): Gen[Array[Field]] = {
    Gen.buildableOf[Array](
      Gen.zip(Gen.identifier, genFieldType))
      .filter(fields => fields.map(_._1).areDistinct())
      .map(fields => fields
        .iterator
        .zipWithIndex
        .map { case ((k, t), i) => Field(k, t, i) }
        .toArray)
  }

  def preGenStruct(required: Boolean, genFieldType: Gen[Type]): Gen[TStruct] = {
    for (fields <- genFields(required, genFieldType)) yield {
      val t = TStruct(fields)
      if (required)
        (+t).asInstanceOf[TStruct]
      else
        t
    }
  }

  def preGenTuple(required: Boolean, genFieldType: Gen[Type]): Gen[TTuple] = {
    for (fields <- genFields(required, genFieldType)) yield {
      val t = TTuple(fields.map(_.typ): _*)
      if (required)
        (+t).asInstanceOf[TTuple]
      else
        t
    }
  }

  private val defaultRequiredGenRatio = 0.2

  def genStruct: Gen[TStruct] = Gen.coin(defaultRequiredGenRatio).flatMap(preGenStruct(_, genArb))

  val genOptionalStruct: Gen[Type] = preGenStruct(required = false, genArb)

  val genRequiredStruct: Gen[Type] = preGenStruct(required = true, genArb)

  val genInsertableStruct: Gen[TStruct] = Gen.coin(defaultRequiredGenRatio).flatMap(required =>
    if (required)
      preGenStruct(required = true, genArb)
    else
      preGenStruct(required = false, genOptional))

  def genSized(size: Int, required: Boolean, genTStruct: Gen[TStruct]): Gen[Type] =
    if (size < 1)
      Gen.const(TStruct.empty(required))
    else if (size < 2)
      genScalar(required)
    else {
      Gen.frequency(
        (4, genScalar(required)),
        (1, genComplexType(required)),
        (1, genArb.map {
          TArray(_)
        }),
        (1, genArb.map {
          TSet(_)
        }),
        (1, genArb.map {
          TInterval(_)
        }),
        (1, preGenTuple(required, genArb)),
        (1, Gen.zip(genRequired, genArb).map { case (k, v) => TDict(k, v) }),
        (1, genTStruct.resize(size)))
    }

  def preGenArb(required: Boolean, genStruct: Gen[TStruct] = genStruct): Gen[Type] =
    Gen.sized(genSized(_, required, genStruct))

  def genArb: Gen[Type] = Gen.coin(0.2).flatMap(preGenArb(_))

  val genOptional: Gen[Type] = preGenArb(required = false)

  val genRequired: Gen[Type] = preGenArb(required = true)

  val genInsertable: Gen[TStruct] = genInsertableStruct

  def genWithValue: Gen[(Type, Annotation)] = for {
    s <- Gen.size
    // prefer smaller type and bigger values
    fraction <- Gen.choose(0.1, 0.3)
    x = (fraction * s).toInt
    y = s - x
    t <- Type.genStruct.resize(x)
    v <- t.genValue.resize(y)
  } yield (t, v)

  implicit def arbType = Arbitrary(genArb)
}

abstract class Type extends BaseType with Serializable with Requiredness {
  self =>

  def physicalType: PType

  def children: Seq[Type] = FastSeq()

  def clear(): Unit = children.foreach(_.clear())

  def unify(concrete: Type): Boolean = {
    this.isOfType(concrete)
  }

  def _isCanonical: Boolean = true

  final def isCanonical: Boolean = _isCanonical && children.forall(_.isCanonical)

  def isBound: Boolean = children.forall(_.isBound)

  def subst(): Type = this.setRequired(false)

  def insert(signature: Type, fields: String*): (Type, Inserter) = insert(signature, fields.toList)

  def insert(signature: Type, path: List[String]): (Type, Inserter) = {
    if (path.nonEmpty)
      TStruct.empty().insert(signature, path)
    else
      (signature, (a, toIns) => toIns)
  }

  def query(fields: String*): Querier = query(fields.toList)

  def query(path: List[String]): Querier = {
    val (t, q) = queryTyped(path)
    q
  }

  def queryTyped(fields: String*): (Type, Querier) = queryTyped(fields.toList)

  def queryTyped(path: List[String]): (Type, Querier) = {
    if (path.nonEmpty)
      throw new AnnotationPathException(s"invalid path ${ path.mkString(".") } from type ${ this }")
    else
      (this, identity[Annotation])
  }

  final def pretty(sb: StringBuilder, indent: Int, compact: Boolean) {
    if (required)
      sb.append("+")
    _pretty(sb, indent, compact)
  }

  def _toPretty: String

  def _pretty(sb: StringBuilder, indent: Int, compact: Boolean) {
    sb.append(_toPretty)
  }

  def fieldOption(fields: String*): Option[Field] = fieldOption(fields.toList)

  def fieldOption(path: List[String]): Option[Field] =
    None

  def schema: DataType = SparkAnnotationImpex.exportType(this)

  def str(a: Annotation): String = if (a == null) "NA" else a.toString

  def _showStr(a: Annotation): String = str(a)

  def showStr(a: Annotation): String = if (a == null) "NA" else _showStr(a)

  def showStr(a: Annotation, trunc: Int): String = {
    val s = showStr(a)
    if (s.length > trunc)
      s.substring(0, trunc - 3) + "..."
    else
      s
  }

  def toJSON(a: Annotation): JValue = JSONAnnotationImpex.exportAnnotation(a, this)

  def genNonmissingValue: Gen[Annotation] = ???

  def genValue: Gen[Annotation] =
    if (required)
      genNonmissingValue
    else
      Gen.nextCoin(0.05).flatMap(isEmpty => if (isEmpty) Gen.const(null) else genNonmissingValue)

  def isRealizable: Boolean = children.forall(_.isRealizable)

  /* compare values for equality, but compare Float and Double values by the absolute value of their difference is within tolerance or with D_== */
  def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double = utils.defaultTolerance, absolute: Boolean = false): Boolean = a1 == a2

  def scalaClassTag: ClassTag[_ <: AnyRef]

  def canCompare(other: Type): Boolean = this == other

  def ordering: ExtendedOrdering

  def jsonReader: JSONReader[Annotation] = new JSONReader[Annotation] {
    def fromJSON(a: JValue): Annotation = JSONAnnotationImpex.importAnnotation(a, self)
  }

  def jsonWriter: JSONWriter[Annotation] = new JSONWriter[Annotation] {
    def toJSON(pk: Annotation): JValue = JSONAnnotationImpex.exportAnnotation(pk, self)
  }

  /*  Fundamental types are types that can be handled natively by RegionValueBuilder: primitive
      types, Array and Struct. */
  def fundamentalType: Type = this

  def _typeCheck(a: Any): Boolean

  final def typeCheck(a: Any): Boolean = (!required && a == null) || _typeCheck(a)

  final def unary_+(): Type = setRequired(true)

  final def unary_-(): Type = setRequired(false)

  final def setRequired(required: Boolean): Type = {
    if (this.required == required)
      this
    else this match {
      case TBinary(_) => TBinary(required)
      case TBoolean(_) => TBoolean(required)
      case TInt32(_) => TInt32(required)
      case TInt64(_) => TInt64(required)
      case TFloat32(_) => TFloat32(required)
      case TFloat64(_) => TFloat64(required)
      case TString(_) => TString(required)
      case TCall(_) => TCall(required)
      case t: TArray => t.copy(required = required)
      case t: TSet => t.copy(required = required)
      case t: TDict => t.copy(required = required)
      case t: TLocus => t.copy(required = required)
      case t: TInterval => t.copy(required = required)
      case t: TStruct => t.copy(required = required)
      case t: TTuple => t.copy(required = required)
      case t: TNDArray => t.copy(required = required)
    }
  }

  final def isOfType(t: Type): Boolean = {
    this match {
      case TBinary(_) => t == TBinaryOptional || t == TBinaryRequired
      case TBoolean(_) => t == TBooleanOptional || t == TBooleanRequired
      case TInt32(_) => t == TInt32Optional || t == TInt32Required
      case TInt64(_) => t == TInt64Optional || t == TInt64Required
      case TFloat32(_) => t == TFloat32Optional || t == TFloat32Required
      case TFloat64(_) => t == TFloat64Optional || t == TFloat64Required
      case TString(_) => t == TStringOptional || t == TStringRequired
      case TCall(_) => t == TCallOptional || t == TCallRequired
      case t2: TLocus => t.isInstanceOf[TLocus] && t.asInstanceOf[TLocus].rg == t2.rg
      case t2: TInterval => t.isInstanceOf[TInterval] && t.asInstanceOf[TInterval].pointType.isOfType(t2.pointType)
      case t2: TStruct =>
        t.isInstanceOf[TStruct] &&
          t.asInstanceOf[TStruct].size == t2.size &&
          t.asInstanceOf[TStruct].fields.zip(t2.fields).forall { case (f1: Field, f2: Field) => f1.typ.isOfType(f2.typ) && f1.name == f2.name }
      case t2: TTuple =>
        t.isInstanceOf[TTuple] &&
          t.asInstanceOf[TTuple].size == t2.size &&
          t.asInstanceOf[TTuple].types.zip(t2.types).forall { case (typ1, typ2) => typ1.isOfType(typ2) }
      case t2: TArray => t.isInstanceOf[TArray] && t.asInstanceOf[TArray].elementType.isOfType(t2.elementType)
      case t2: TSet => t.isInstanceOf[TSet] && t.asInstanceOf[TSet].elementType.isOfType(t2.elementType)
      case t2: TDict => t.isInstanceOf[TDict] && t.asInstanceOf[TDict].keyType.isOfType(t2.keyType) && t.asInstanceOf[TDict].valueType.isOfType(t2.valueType)
      case t2: TNDArray =>
        t.isInstanceOf[TNDArray] &&
        t.asInstanceOf[TNDArray].elementType.isOfType(t2.elementType) &&
        t.asInstanceOf[TNDArray].nDims == t2.nDims
      case TVoid => t == TVoid
    }
  }

  def canCastTo(t: Type): Boolean = this match {
    case TInterval(tt1, required) => t match {
      case TInterval(tt2, `required`) => tt1.canCastTo(tt2)
      case _ => false
    }
    case TStruct(f1, required) => t match {
      case TStruct(f2, `required`) => f1.size == f2.size && f1.indices.forall(i => f1(i).typ.canCastTo(f2(i).typ))
      case _ => false
    }
    case TTuple(f1, required) => t match {
      case TTuple(f2, `required`) => f1.size == f2.size && f1.indices.forall(i => f1(i).typ.canCastTo(f2(i).typ))
      case _ => false
    }
    case TArray(t1, required) => t match {
      case TArray(t2, `required`) => t1.canCastTo(t2)
      case _ => false
    }
    case TSet(t1, required) => t match {
      case TSet(t2, `required`) => t1.canCastTo(t2)
      case _ => false
    }
    case TDict(k1, v1, required) => t match {
      case TDict(k2, v2, `required`) => k1.canCastTo(k2) && v1.canCastTo(v2)
      case _ => false
    }
    case _ => this == t
  }

  def deepOptional(): Type =
    this match {
      case t: TArray => TArray(t.elementType.deepOptional())
      case t: TSet => TSet(t.elementType.deepOptional())
      case t: TDict => TDict(t.keyType.deepOptional(), t.valueType.deepOptional())
      case t: TStruct =>
        TStruct(t.fields.map(f => Field(f.name, f.typ.deepOptional(), f.index)))
      case t: TTuple =>
        TTuple(t._types.map(fd => fd.copy(typ = fd.typ.deepOptional())))
      case t =>
        t.setRequired(false)
    }

  def structOptional(): Type =
    this match {
      case t: TStruct =>
        TStruct(t.fields.map(f => Field(f.name, f.typ.deepOptional(), f.index)))
      case t =>
        t.setRequired(false)
    }
}
