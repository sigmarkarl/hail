package is.hail.expr.types.physical

import is.hail.annotations.{Region, UnsafeUtils}
import is.hail.asm4s._
import is.hail.expr.ir.{PCanonicalBaseStructCode, PCode}
import is.hail.expr.types.BaseStruct
import is.hail.utils._

abstract class PCanonicalBaseStruct(val types: Array[PType]) extends PBaseStruct {
  val (missingIdx: Array[Int], nMissing: Int) = BaseStruct.getMissingIndexAndCount(types.map(_.required))
  val nMissingBytes: Int = UnsafeUtils.packBitsToBytes(nMissing)
  val byteOffsets: Array[Long] = new Array[Long](size)
  override val byteSize: Long = PBaseStruct.getByteSizeAndOffsets(types, nMissingBytes, byteOffsets)
  override val alignment: Long = PBaseStruct.alignment(types)


  def allocate(region: Region): Long = {
    region.allocate(alignment, byteSize)
  }

  def allocate(region: Code[Region]): Code[Long] =
    region.allocate(alignment, byteSize)

  def initialize(structAddress: Long, setMissing: Boolean = false): Unit = {
    if (allFieldsRequired) {
      return
    }

    Region.setMemory(structAddress, nMissingBytes.toLong, if (setMissing) 0xFF.toByte else 0.toByte)
  }

  def stagedInitialize(structAddress: Code[Long], setMissing: Boolean = false): Code[Unit] = {
    if (allFieldsRequired) {
      return Code._empty
    }

    Region.setMemory(structAddress, const(nMissingBytes.toLong), const(if (setMissing) 0xFF.toByte else 0.toByte))
  }

  def isFieldDefined(offset: Long, fieldIdx: Int): Boolean =
    fieldRequired(fieldIdx) || !Region.loadBit(offset, missingIdx(fieldIdx))

  def isFieldMissing(offset: Code[Long], fieldIdx: Int): Code[Boolean] =
    if (fieldRequired(fieldIdx))
      false
    else
      Region.loadBit(offset, missingIdx(fieldIdx).toLong)

  def setFieldMissing(offset: Long, fieldIdx: Int) {
    assert(!fieldRequired(fieldIdx))
    Region.setBit(offset, missingIdx(fieldIdx))
  }

  def setFieldMissing(offset: Code[Long], fieldIdx: Int): Code[Unit] = {
    assert(!fieldRequired(fieldIdx))
    Region.setBit(offset, missingIdx(fieldIdx).toLong)
  }

  def setFieldPresent(offset: Long, fieldIdx: Int) {
    if (!fieldRequired(fieldIdx))
      Region.clearBit(offset, missingIdx(fieldIdx))
  }

  def setFieldPresent(offset: Code[Long], fieldIdx: Int): Code[Unit] = {
    if (!fieldRequired(fieldIdx))
      Region.clearBit(offset, missingIdx(fieldIdx).toLong)
    else
      Code._empty
  }

  def fieldOffset(structAddress: Long, fieldIdx: Int): Long =
    structAddress + byteOffsets(fieldIdx)

  def fieldOffset(structAddress: Code[Long], fieldIdx: Int): Code[Long] =
    structAddress + byteOffsets(fieldIdx)

  def loadField(offset: Long, fieldIdx: Int): Long = {
    val off = fieldOffset(offset, fieldIdx)
    types(fieldIdx).fundamentalType match {
      case _: PArray | _: PBinary => Region.loadAddress(off)
      case _ => off
    }
  }

  def loadField(offset: Code[Long], fieldIdx: Int): Code[Long] =
    loadField(fieldOffset(offset, fieldIdx), types(fieldIdx))

  private def loadField(fieldOffset: Code[Long], fieldType: PType): Code[Long] = {
    fieldType.fundamentalType match {
      case _: PArray | _: PBinary => Region.loadAddress(fieldOffset)
      case _ => fieldOffset
    }
  }

  def deepPointerCopy(mb: MethodBuilder, region: Code[Region], dstStructAddress: Code[Long]): Code[Unit] = {
    var c: Code[Unit] = Code._empty
    var i = 0
    while(i < this.size) {
      val dstFieldType = this.fields(i).typ.fundamentalType
      if(dstFieldType.containsPointers) {
        val dstFieldAddress = mb.newField[Long]
        c = Code(
          c,
          this.isFieldDefined(dstStructAddress, i).orEmpty(
            Code(
              dstFieldAddress := this.fieldOffset(dstStructAddress, i),
              dstFieldType match {
                case t@(_: PBinary | _: PArray) =>
                  Region.storeAddress(dstFieldAddress, t.copyFromType(mb, region, dstFieldType, Region.loadAddress(dstFieldAddress), forceDeep = true))
                case t: PCanonicalBaseStruct =>
                  t.deepPointerCopy(mb, region, dstFieldAddress)
              }
            )
          )
        )
      }
      i += 1
    }

    c
  }

  def deepPointerCopy(region: Region, dstStructAddress: Long) {
    var i = 0
    while(i < this.size) {
      val dstFieldType = this.fields(i).typ.fundamentalType
      if(dstFieldType.containsPointers && this.isFieldDefined(dstStructAddress, i)) {
        val dstFieldAddress = this.fieldOffset(dstStructAddress, i)
        dstFieldType match {
          case t@(_: PBinary | _: PArray) =>
            Region.storeAddress(dstFieldAddress, t.copyFromType(region, dstFieldType, Region.loadAddress(dstFieldAddress), forceDeep = true))
          case t: PCanonicalBaseStruct =>
            t.deepPointerCopy(region, dstFieldAddress)
        }
      }
      i += 1
    }
  }

  def copyFromType(mb: MethodBuilder, region: Code[Region], srcPType: PType, srcStructAddress: Code[Long], forceDeep: Boolean): Code[Long] = {
    val sourceType = srcPType.asInstanceOf[PBaseStruct]
    assert(sourceType.size == this.size)

    if (this == sourceType && !forceDeep)
      srcStructAddress
    else {
      val addr = mb.newLocal[Long]
      Code(
        addr := allocate(region),
        constructAtAddress(mb, addr, region, sourceType, srcStructAddress, forceDeep),
        addr
      )
    }
  }

  def copyFromTypeAndStackValue(mb: MethodBuilder, region: Code[Region], srcPType: PType, stackValue: Code[_], forceDeep: Boolean): Code[_] =
    this.copyFromType(mb, region, srcPType, stackValue.asInstanceOf[Code[Long]], forceDeep)

  def copyFromType(region: Region, srcPType: PType, srcStructAddress: Long, forceDeep: Boolean): Long = {
    val sourceType = srcPType.asInstanceOf[PBaseStruct]

    if (this == sourceType && !forceDeep)
      srcStructAddress
    else {
      val newAddr = allocate(region)
      constructAtAddress(newAddr, region, sourceType, srcStructAddress, forceDeep)
      newAddr
    }
  }

  def constructAtAddress(mb: MethodBuilder, addr: Code[Long], region: Code[Region], srcPType: PType, srcAddress: Code[Long], forceDeep: Boolean): Code[Unit] = {
    val srcStruct = srcPType.asInstanceOf[PBaseStruct]
    val addrVar = mb.newLocal[Long]

    if (srcStruct == this) {
      var c: Code[Unit] = Code(
        addrVar := addr,
        Region.copyFrom(srcAddress, addrVar, byteSize))
      if (forceDeep)
        c = Code(c, deepPointerCopy(mb, region, addrVar))
      c
    } else {
      val srcAddrVar = mb.newLocal[Long]
      Code(
        srcAddrVar := srcAddress,
        addrVar := addr,
        stagedInitialize(addrVar, setMissing = true),
        Code(fields.zip(srcStruct.fields).map { case (dest, src) =>
          assert(dest.typ.required <= src.typ.required)
          val idx = dest.index
          assert(idx == src.index)
          srcStruct.isFieldDefined(srcAddrVar, idx).orEmpty(Code(
            setFieldPresent(addrVar, idx),
            dest.typ.constructAtAddress(mb, fieldOffset(addrVar, idx), region, src.typ, srcStruct.loadField(srcAddrVar, idx), forceDeep))
          )
        }))
    }
  }

  def constructAtAddress(addr: Long, region: Region, srcPType: PType, srcAddress: Long, forceDeep: Boolean): Unit = {
    val srcStruct = srcPType.asInstanceOf[PBaseStruct]
    if (srcStruct == this) {
      Region.copyFrom(srcAddress, addr, byteSize)
      if (forceDeep)
        deepPointerCopy(region, addr)
    } else {
      initialize(addr, setMissing = true)
      var idx = 0
      while (idx < types.length) {
        val dest = types(idx)
        val src = srcStruct.types(idx)
        assert(dest.required <= src.required)

        if (srcStruct.isFieldDefined(srcAddress, idx)) {
          setFieldPresent(addr, idx)
          dest.constructAtAddress(fieldOffset(addr, idx), region, src, srcStruct.loadField(srcAddress, idx), forceDeep)
        }
        idx += 1
      }
    }
  }

  override def load(src: Code[Long]): PCode =
    new PCanonicalBaseStructCode(this, src)
}
