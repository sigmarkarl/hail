package is.hail.expr.types.encoded

import is.hail.annotations.Region
import is.hail.asm4s._
import is.hail.expr.ir.EmitMethodBuilder
import is.hail.expr.types.{BaseStruct, BaseType}
import is.hail.expr.types.physical._
import is.hail.expr.types.virtual._
import is.hail.io.{InputBuffer, OutputBuffer}
import is.hail.utils._

final case class EField(name: String, typ: EType, index: Int) {
  def pretty(sb: StringBuilder, indent: Int, compact: Boolean) {
    if (compact) {
      sb.append(prettyIdentifier(name))
      sb.append(":")
    } else {
      sb.append(" " * indent)
      sb.append(prettyIdentifier(name))
      sb.append(": ")
    }
    typ.pretty(sb, indent, compact)
  }
}

final case class EBaseStruct(fields: IndexedSeq[EField], override val required: Boolean = false) extends EType {
  assert(fields.zipWithIndex.forall { case (f, i) => f.index == i })

  val types: Array[EType] = fields.map(_.typ).toArray
  def size: Int = types.length
  val fieldNames: Array[String] = fields.map(_.name).toArray
  val fieldIdx: Map[String, Int] = fields.map(f => (f.name, f.index)).toMap
  def hasField(name: String): Boolean = fieldIdx.contains(name)
  def fieldType(name: String): EType = types(fieldIdx(name))
  val missingIdx = new Array[Int](size)
  val nMissing: Int = BaseStruct.getMissingness[EType](types, missingIdx)
  val nMissingBytes = (nMissing + 7) >>> 3

  if (!fieldNames.areDistinct()) {
    val duplicates = fieldNames.duplicates()
    fatal(s"cannot create struct with duplicate ${plural(duplicates.size, "field")}: " +
      s"${fieldNames.map(prettyIdentifier).mkString(", ")}", fieldNames.duplicates())
  }

  override def _decodeCompatible(pt: PType): Boolean = {
    if (!pt.isInstanceOf[PBaseStruct])
      false
    else {
      val ps = pt.asInstanceOf[PBaseStruct]
      ps.required == required &&
        ps.size <= size &&
        ps.fields.forall { f =>
          hasField(f.name) && fieldType(f.name).decodeCompatible(f.typ)
        }
    }
  }

  override def _encodeCompatible(pt: PType): Boolean = {
    if (!pt.isInstanceOf[PBaseStruct])
      false
    else {
      val ps = pt.asInstanceOf[PBaseStruct]
      ps.required == required &&
        size <= ps.size &&
        fields.forall { f =>
          ps.hasField(f.name) && f.typ.encodeCompatible(ps.fieldType(f.name))
        }
    }
  }

  def _decodedPType(requestedType: Type): PType = requestedType match {
    case t: TInterval =>
      val repr = t.representation
      val pointType = _decodedPType(repr).asInstanceOf[PStruct].fieldType("start")
      PInterval(pointType, required)
    case t: TLocus => PLocus(t.rg, required)
    case t: TStruct =>
      val pFields = t.fields.map { case Field(name, typ, idx) =>
        val pt = fieldType(name).decodedPType(typ)
        PField(name, pt, idx)
      }
      PStruct(pFields, required)
    case t: TTuple =>
      val pFields = t.fields.map { case Field(name, typ, idx) =>
        val pt = fieldType(name).decodedPType(typ)
        PTupleField(idx, pt)
      }
      PTuple(pFields, required)
    case t: TNDArray =>
      val elementType = _decodedPType(t.representation).asInstanceOf[PStruct].fieldType("data").asInstanceOf[PArray].elementType
      PNDArray(elementType, t.nDims, required)
  }

  def _buildEncoder(pt: PType, mb: EmitMethodBuilder, v: Code[_], out: Code[OutputBuffer]): Code[Unit] = {
    val ft = pt.asInstanceOf[PBaseStruct]
    val writeMissingBytes = if (ft.size == size) {
      out.writeBytes(coerce[Long](v), ft.nMissingBytes)
    } else {
      val groupSize = 64
      var methodIdx = 0
      var currentMB = mb.fb.newMethod(s"missingbits_group_$methodIdx", Array[TypeInfo[_]](LongInfo, classInfo[OutputBuffer]), UnitInfo)
      var wrappedC: Code[Unit] = Code._empty[Unit]
      var methodC: Code[Unit] = Code._empty[Unit]

      var j = 0
      var n = 0
      while (j < size) {
        if (n % groupSize == 0) {
          currentMB.emit(methodC)
          methodC = Code._empty[Unit]
          wrappedC = Code(wrappedC, currentMB.invoke[Unit](v, out))
          methodIdx += 1
          currentMB = mb.fb.newMethod(s"missingbits_group_$methodIdx", Array[TypeInfo[_]](LongInfo, classInfo[OutputBuffer]), UnitInfo)
        }
        var b = const(0)
        var k = 0
        while (k < 8 && j < size) {
          val f = fields(j)
          if (!f.typ.required) {
            val i = ft.fieldIdx(f.name)
            b = b | (ft.isFieldMissing(currentMB.getArg[Long](1), i).toI << k)
            k += 1
          }
          j += 1
        }
        if (k > 0) {
          methodC = Code(methodC, currentMB.getArg[OutputBuffer](2).load().writeByte(b.toB))
          n += 1
        }
      }
      currentMB.emit(methodC)
      wrappedC = Code(wrappedC, currentMB.invoke[Unit](v, out))

      assert(n == nMissingBytes)
      wrappedC
    }

    val writeFields = coerce[Unit](Code(fields.grouped(64).zipWithIndex.map { case (fieldGroup, groupIdx) =>
      val groupMB = mb.fb.newMethod(s"write_fields_group_$groupIdx", Array[TypeInfo[_]](LongInfo, classInfo[OutputBuffer]), UnitInfo)

      val addr = groupMB.getArg[Long](1)
      val out2 = groupMB.getArg[OutputBuffer](2)
      groupMB.emit(coerce[Unit](Code(
        fieldGroup.map { ef =>
          val i = ft.fieldIdx(ef.name)
          val pf = ft.fields(i)
          val encodeField = ef.typ.buildEncoder(pf.typ, groupMB)
          val v = Region.loadIRIntermediate(pf.typ)(ft.fieldOffset(addr, i))
          ft.isFieldDefined(addr, i).mux(
            encodeField(v, out2),
            Code._empty[Unit]
          )
        }: _*
      )))

      groupMB.invoke[Unit](v, out)
    }.toArray: _*))

    Code(writeMissingBytes, writeFields, Code._empty[Unit])
  }

  def _buildDecoder(
    pt: PType,
    mb: EmitMethodBuilder,
    region: Code[Region],
    in: Code[InputBuffer]
  ): Code[Long] = {
    val addr = mb.newLocal[Long]("addr")

    Code(
      addr := pt.asInstanceOf[PBaseStruct].allocate(region),
      _buildInplaceDecoder(pt, mb, region, addr, in),
      addr.load()
    )
  }

  override def _buildInplaceDecoder(
    pt: PType,
    mb: EmitMethodBuilder,
    region: Code[Region],
    addr: Code[Long],
    in: Code[InputBuffer]
  ): Code[Unit] = {

    val t = pt.asInstanceOf[PBaseStruct]
    val mbytes = mb.newLocal[Long]("mbytes")

    val readFields = coerce[Unit](Code(fields.grouped(64).zipWithIndex.map { case (fieldGroup, groupIdx) =>
      val groupMB = mb.fb.newMethod(s"read_fields_group_$groupIdx", Array[TypeInfo[_]](classInfo[Region], LongInfo, LongInfo, classInfo[InputBuffer]), UnitInfo)
      val regionArg = groupMB.getArg[Region](1)
      val addrArg = groupMB.getArg[Long](2)
      val mbytesArg = groupMB.getArg[Long](3)
      val inArg = groupMB.getArg[InputBuffer](4)
      groupMB.emit(Code(fieldGroup.map { f =>
        if (t.hasField(f.name)) {
          val rf = t.field(f.name)
          val readElemF = f.typ.buildInplaceDecoder(rf.typ, mb.fb)
          val rFieldAddr = t.fieldOffset(addrArg, rf.index)
          if (f.typ.required)
            readElemF(regionArg, rFieldAddr, inArg)
          else
            Region.loadBit(mbytesArg, const(missingIdx(f.index).toLong)).mux(
              t.setFieldMissing(addrArg, rf.index),
              Code(
                t.setFieldPresent(addrArg, rf.index),
                readElemF(regionArg, rFieldAddr, inArg)))
        } else {
          val skip = f.typ.buildSkip(groupMB)
          if (f.typ.required)
            skip(regionArg, inArg)
          else
            Region.loadBit(mbytesArg, const(missingIdx(f.index).toLong)).mux(
              Code._empty[Unit],
              skip(regionArg, inArg))
        }
      }))
      groupMB.invoke[Unit](region, addr, mbytes, in)
    }.toArray: _*))

    Code(
      mbytes := region.allocate(const(1), const(nMissingBytes)),
      in.readBytes(region, mbytes, nMissingBytes),
      readFields,
      Code._empty[Unit])
  }
  def _buildSkip(mb: EmitMethodBuilder, r: Code[Region], in: Code[InputBuffer]): Code[Unit] = {
    val mbytes = mb.newLocal[Long]("mbytes")
    val skipFields = fields.map { f =>
      val skip = f.typ.buildSkip(mb)
      if (f.typ.required)
        skip(r, in)
      else
        Region.loadBit(mbytes, missingIdx(f.index).toLong).mux(
          Code._empty,
          skip(r, in))
    }

    Code(
      mbytes := r.allocate(const(1), const(nMissingBytes)),
      in.readBytes(r, mbytes, nMissingBytes),
      Code(skipFields: _*),
      Code._empty)
  }

  def _asIdent: String = {
    val sb = new StringBuilder
    sb.append("struct_of_")
    types.foreachBetween { ty =>
      sb.append(ty.asIdent)
    } {
      sb.append("AND")
    }
    sb.append("END")
    sb.result()
  }

  def _toPretty: String = {
    val sb = new StringBuilder
    _pretty(sb, 0, compact = true)
    sb.result()
  }

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean) {
    if (compact) {
      sb.append("EBaseStruct{")
      fields.foreachBetween(_.pretty(sb, indent, compact))(sb += ',')
      sb += '}'
    } else {
      if (fields.length == 0)
        sb.append("EBaseStruct { }")
      else {
        sb.append("EBaseStruct {")
        sb += '\n'
        fields.foreachBetween(_.pretty(sb, indent + 4, compact))(sb.append(",\n"))
        sb += '\n'
        sb.append(" " * indent)
        sb += '}'
      }
    }
  }
}
