package is.hail.annotations

import is.hail.asm4s.Code._
import is.hail.asm4s.{Code, FunctionBuilder, _}
import is.hail.expr.ir
import is.hail.expr.ir.{EmitFunctionBuilder, EmitMethodBuilder, EmitRegion}
import is.hail.expr.types.physical._
import is.hail.expr.types.virtual.{TBoolean, TFloat32, TFloat64, TInt32, TInt64, Type}
import is.hail.utils._

object StagedRegionValueBuilder {
  def deepCopy(fb: EmitFunctionBuilder[_], region: Code[Region], typ: PType, value: Code[_], dest: Code[Long]): Code[Unit] = {
    val t = typ.fundamentalType
    val valueTI = ir.typeToTypeInfo(t)
    val mb = fb.getOrDefineMethod("deepCopy", ("deepCopy", typ),
      Array[TypeInfo[_]](classInfo[Region], valueTI, LongInfo), UnitInfo) { mb =>
      val r = mb.getArg[Region](1)
      val value = mb.getArg(2)(valueTI)
      val dest = mb.getArg[Long](3)
      mb.emit(t.constructAtAddressFromValue(mb, dest, r, t, value, true))
    }
    mb.invoke(region, value, dest)
  }

  def deepCopyFromOffset(fb: EmitFunctionBuilder[_], region: Code[Region], typ: PType, value: Code[Long]): Code[Long] = {
    val t = typ.fundamentalType
    val mb = fb.getOrDefineMethod("deepCopyFromOffset", ("deepCopyFromOffset", typ),
      Array[TypeInfo[_]](classInfo[Region], LongInfo), LongInfo) { mb =>
      val r = mb.getArg[Region](1)
      val value = mb.getArg[Long](2)
      mb.emit(t.copyFromType(mb, r, t, value, true))
    }
    mb.invoke(region, value)
  }

  def deepCopyFromOffset(er: EmitRegion, typ: PType, value: Code[Long]): Code[Long] =
    deepCopyFromOffset(er.mb.fb, er.region, typ, value)

  def deepCopy(er: EmitRegion, typ: PType, value: Code[_], dest: Code[Long]): Code[Unit] =
    deepCopy(er.mb.fb, er.region, typ, value, dest)
}

class StagedRegionValueBuilder private(val mb: MethodBuilder, val typ: PType, var region: Value[Region], val pOffset: Value[Long]) {
  def this(mb: MethodBuilder, typ: PType, parent: StagedRegionValueBuilder) = {
    this(mb, typ, parent.region, parent.currentOffset)
  }

  def this(fb: FunctionBuilder[_], rowType: PType) = {
    this(fb.apply_method, rowType, fb.apply_method.getArg[Region](1), null)
  }

  def this(fb: FunctionBuilder[_], rowType: PType, pOffset: Value[Long]) = {
    this(fb.apply_method, rowType, fb.apply_method.getArg[Region](1), pOffset)
  }

  def this(mb: MethodBuilder, rowType: PType) = {
    this(mb, rowType, mb.getArg[Region](1), null)
  }

  def this(mb: MethodBuilder, rowType: PType, r: Value[Region]) = {
    this(mb, rowType, r, null)
  }

  def this(er: ir.EmitRegion, rowType: PType) = {
    this(er.mb, rowType, er.region, null)
  }

  private val ftype = typ.fundamentalType

  private var staticIdx: Int = 0
  private var idx: ClassFieldRef[Int] = _
  private var elementsOffset: ClassFieldRef[Long] = _
  private val startOffset: ClassFieldRef[Long] = mb.newField[Long]

  ftype match {
    case t: PBaseStruct => elementsOffset = mb.newField[Long]
    case t: PArray =>
      elementsOffset = mb.newField[Long]
      idx = mb.newField[Int]
    case _ =>
  }

  def offset: Value[Long] = startOffset

  def arrayIdx: Value[Int] = idx

  def currentOffset: Value[Long] = {
    ftype match {
      case _: PBaseStruct => elementsOffset
      case _: PArray => elementsOffset
      case _ => startOffset
    }
  }

  def init(): Code[Unit] = Code(
    startOffset := -1L,
    elementsOffset := -1L,
    if (idx != null) idx := -1 else Code._empty
  )

  def start(): Code[Unit] = {
    assert(!ftype.isInstanceOf[PArray]) // Need to use other start with length.
    ftype match {
      case _: PBaseStruct => start(true)
      case _: PBinary =>
        assert(pOffset == null)
        startOffset := -1L
      case _ =>
        startOffset := region.allocate(ftype.alignment, ftype.byteSize)
    }
  }

  def start(length: Code[Int], init: Boolean = true): Code[Unit] =
    Code.memoize(length, "srvb_start_length") { length =>
      val t = ftype.asInstanceOf[PArray]
      var c = startOffset.store(t.allocate(region, length))
      if (pOffset != null) {
        c = Code(c, Region.storeAddress(pOffset, startOffset))
      }
      if (init)
        c = Code(c, t.stagedInitialize(startOffset, length))
      c = Code(c, elementsOffset.store(startOffset + t.elementsOffset(length)))
      Code(c, idx.store(0))
    }

  def start(init: Boolean): Code[Unit] = {
    val t = ftype.asInstanceOf[PCanonicalBaseStruct]
    var c = if (pOffset == null)
      startOffset.store(region.allocate(t.alignment, t.byteSize))
    else
      startOffset.store(pOffset)
    staticIdx = 0
    if (t.size > 0)
      c = Code(c, elementsOffset := startOffset + t.byteOffsets(0))
    if (init)
      c = Code(c, t.stagedInitialize(startOffset))
    c
  }

  def setMissing(): Code[Unit] = {
    ftype match {
      case t: PArray => t.setElementMissing(startOffset, idx)
      case t: PCanonicalBaseStruct =>
        if (t.fieldRequired(staticIdx))
          Code._fatal[Unit](s"Required field cannot be missing: $t, $staticIdx")
        else
          t.setFieldMissing(startOffset, staticIdx)
    }
  }

  def currentPType(): PType = {
    ftype match {
      case t: PArray => t.elementType
      case t: PCanonicalBaseStruct =>
        t.types(staticIdx)
      case t => t
    }
  }

  def checkType(knownType: Type): Unit = {
    val current = currentPType().virtualType
    if (current != knownType)
      throw new RuntimeException(s"bad SRVB addition: expected $current, tried to add $knownType")
  }

  def addBoolean(v: Code[Boolean]): Code[Unit] = {
    checkType(TBoolean)
    Region.storeByte(currentOffset, v.toI.toB)
  }

  def addInt(v: Code[Int]): Code[Unit] = {
    checkType(TInt32)
    Region.storeInt(currentOffset, v)
  }

  def addLong(v: Code[Long]): Code[Unit] = {
    checkType(TInt64)
    Region.storeLong(currentOffset, v)
  }

  def addFloat(v: Code[Float]): Code[Unit] = {
    checkType(TFloat32)
    Region.storeFloat(currentOffset, v)
  }

  def addDouble(v: Code[Double]): Code[Unit] = {
    checkType(TFloat64)
    Region.storeDouble(currentOffset, v)
  }

  def addBinary(bytes: Code[Array[Byte]]): Code[Unit] = {
    val b = mb.newField[Array[Byte]]
    val boff = mb.newField[Long]
    val pbT = currentPType().asInstanceOf[PBinary]

    Code(
      b := bytes,
      boff := pbT.allocate(region, b.length()),
      ftype match {
        case _: PBinary => startOffset := boff
        case _ =>
          Region.storeAddress(currentOffset, boff)
      },
      pbT.store(boff, b))
  }


  def addAddress(v: Code[Long]): Code[Unit] = Region.storeAddress(currentOffset, v)

  def addString(str: Code[String]): Code[Unit] = addBinary(str.invoke[Array[Byte]]("getBytes"))

  def addArray(t: PArray, f: (StagedRegionValueBuilder => Code[Unit])): Code[Unit] = {
    if (!(t.fundamentalType isOfType currentPType()))
      throw new RuntimeException(s"Fundamental type doesn't match. current=${currentPType()}, t=${t.fundamentalType}, ftype=$ftype")
    f(new StagedRegionValueBuilder(mb, currentPType(), this))
  }

  def addBaseStruct(t: PBaseStruct, f: (StagedRegionValueBuilder => Code[Unit])): Code[Unit] = {
    if (!(t.fundamentalType isOfType currentPType()))
      throw new RuntimeException(s"Fundamental type doesn't match. current=${currentPType()}, t=${t.fundamentalType}, ftype=$ftype")
    f(new StagedRegionValueBuilder(mb, currentPType(), this))
  }

  def addIRIntermediate(t: PType): (Code[_]) => Code[Unit] = t.fundamentalType match {
    case _: PBoolean => v => addBoolean(v.asInstanceOf[Code[Boolean]])
    case _: PInt32 => v => addInt(v.asInstanceOf[Code[Int]])
    case _: PInt64 => v => addLong(v.asInstanceOf[Code[Long]])
    case _: PFloat32 => v => addFloat(v.asInstanceOf[Code[Float]])
    case _: PFloat64 => v => addDouble(v.asInstanceOf[Code[Double]])
    case t =>
      val current = currentPType()
      val valueTI = ir.typeToTypeInfo(t)
      val m = mb.fb.getOrDefineMethod("addIRIntermediate", ("addIRIntermediate", current, t),
        Array[TypeInfo[_]](classInfo[Region], valueTI, LongInfo), UnitInfo) { mb =>
        val r = mb.getArg[Region](1)
        val value = mb.getArg(2)(valueTI)
        val dest = mb.getArg[Long](3)
        mb.emit(current.constructAtAddressFromValue(mb, dest, r, t, value, false))
      }
      v => coerce[Unit](m.invoke(region, v, currentOffset))
  }

  def addWithDeepCopy(t: PType, v: Code[_]): Code[Unit] = {
    if (!(t.fundamentalType isOfType currentPType()))
      throw new RuntimeException(s"Fundamental type doesn't match. current=${currentPType()}, t=${t.fundamentalType}, ftype=$ftype")
    StagedRegionValueBuilder.deepCopy(
      EmitRegion(mb.asInstanceOf[EmitMethodBuilder], region),
      t, v, currentOffset)
  }

  def advance(): Code[Unit] = {
    ftype match {
      case t: PArray => Code(
        elementsOffset := elementsOffset + t.elementByteSize,
        idx := idx + 1
      )
      case t: PCanonicalBaseStruct =>
        staticIdx += 1
        if (staticIdx < t.size)
          elementsOffset := elementsOffset + (t.byteOffsets(staticIdx) - t.byteOffsets(staticIdx - 1))
        else _empty
    }
  }

  def end(): Code[Long] = {
    startOffset
  }
}
