package is.hail.io

import java.io._
import java.util

import is.hail.annotations.{Memory, Region}
import is.hail.io.compress.LZ4
import is.hail.utils._

trait InputBuffer extends Closeable {
  def close(): Unit

  def seek(offset: Long): Unit

  def readByte(): Byte

  def readInt(): Int

  def readLong(): Long

  def readFloat(): Float

  def readDouble(): Double

  def readBytes(toRegion: Region, toOff: Long, n: Int): Unit

  def skipBoolean(): Unit = skipByte()

  def skipByte(): Unit

  def skipInt(): Unit

  def skipLong(): Unit

  def skipFloat(): Unit

  def skipDouble(): Unit

  def skipBytes(n: Int): Unit

  def readDoubles(to: Array[Double], off: Int, n: Int): Unit

  def readDoubles(to: Array[Double]): Unit = readDoubles(to, 0, to.length)

  def readBoolean(): Boolean = readByte() != 0
}

trait InputBlockBuffer extends Spec with Closeable {
  def close(): Unit

  def seek(offset: Long)

  def readBlock(buf: Array[Byte]): Int
}

final class StreamInputBuffer(in: InputStream) extends InputBuffer {
  private val buff = new Array[Byte](8)

  def close(): Unit = in.close()

  def seek(offset: Long) = in.asInstanceOf[ByteTrackingInputStream].seek(offset)

  def readByte(): Byte = {
    val bytesRead = in.read(buff, 0, 1)
    assert(bytesRead == 1)
    Memory.loadByte(buff, 0)
  }

  def readInt(): Int = {
    val bytesRead = in.read(buff, 0, 4)
    assert(bytesRead == 4)
    Memory.loadInt(buff, 0)
  }

  def readLong(): Long = {
    val bytesRead = in.read(buff)
    assert(bytesRead == 8)
    Memory.loadLong(buff, 0)
  }

  def readFloat(): Float = {
    val bytesRead = in.read(buff, 0, 4)
    assert(bytesRead == 4)
    Memory.loadFloat(buff, 0)
  }

  def readDouble(): Double = {
    val bytesRead = in.read(buff)
    assert(bytesRead == 8)
    Memory.loadDouble(buff, 0)
  }

  def readBytes(toRegion: Region, toOff: Long, n: Int): Unit = {
    Region.storeBytes(toOff, Array.tabulate(n)(_ => readByte()))
  }

  def skipByte(): Unit = {
    val bytesRead = in.skip(1)
    assert(bytesRead == 1L)
  }

  def skipInt(): Unit = {
    val bytesRead = in.skip(4)
    assert(bytesRead == 4L)
  }

  def skipLong(): Unit = {
    val bytesRead = in.skip(8)
    assert(bytesRead == 8L)
  }

  def skipFloat(): Unit = {
    val bytesRead = in.skip(4)
    assert(bytesRead == 4L)
  }

  def skipDouble(): Unit = {
    val bytesRead = in.skip(8)
    assert(bytesRead == 8L)
  }

  def skipBytes(n: Int): Unit = {
    val bytesRead = in.skip(n)
    assert(bytesRead == n)
  }

  def readDoubles(to: Array[Double], off: Int, n: Int): Unit = {
    var i = 0
    while (i < n) {
      to(off + i) = readDouble()
      i += 1
    }
  }
}

final class MemoryInputBuffer(mb: MemoryBuffer) extends InputBuffer {
  def close() {}

  def seek(offset: Long) = ???

  def readByte(): Byte = mb.readByte()

  def readInt(): Int = mb.readInt()

  def readLong(): Long = mb.readLong()

  def readFloat(): Float = mb.readFloat()

  def readDouble(): Double = mb.readDouble()

  def readBytes(toRegion: Region, toOff: Long, n: Int): Unit = mb.readBytes(toOff, n)

  def skipByte(): Unit = mb.skipByte()

  def skipInt(): Unit = mb.skipInt()

  def skipLong(): Unit = mb.skipLong()

  def skipFloat(): Unit = mb.skipFloat()

  def skipDouble(): Unit = mb.skipDouble()

  def skipBytes(n: Int): Unit = mb.skipBytes(n)

  def readDoubles(to: Array[Double], off: Int, n: Int): Unit = ???
}

final class LEB128InputBuffer(in: InputBuffer) extends InputBuffer {
  def close() {
    in.close()
  }

  def seek(offset: Long): Unit = in.seek(offset)

  def readByte(): Byte = {
    in.readByte()
  }

  def readInt(): Int = {
    var b: Byte = readByte()
    var x: Int = b & 0x7f
    var shift: Int = 7
    while ((b & 0x80) != 0) {
      b = readByte()
      x |= ((b & 0x7f) << shift)
      shift += 7
    }
    x
  }

  def readLong(): Long = {
    var b: Byte = readByte()
    var x: Long = b & 0x7fL
    var shift: Int = 7
    while ((b & 0x80) != 0) {
      b = readByte()
      x |= ((b & 0x7fL) << shift)
      shift += 7
    }
    x
  }

  def readFloat(): Float = in.readFloat()

  def readDouble(): Double = in.readDouble()

  def readBytes(toRegion: Region, toOff: Long, n: Int): Unit = in.readBytes(toRegion, toOff, n)

  def skipByte(): Unit = in.skipByte()

  def skipInt() {
    var b: Byte = readByte()
    while ((b & 0x80) != 0)
      b = readByte()
  }

  def skipLong() {
    var b: Byte = readByte()
    while ((b & 0x80) != 0)
      b = readByte()
  }

  def skipFloat(): Unit = in.skipFloat()

  def skipDouble(): Unit = in.skipDouble()

  def skipBytes(n: Int): Unit = in.skipBytes(n)

  def readDoubles(to: Array[Double], toOff: Int, n: Int): Unit = in.readDoubles(to, toOff, n)
}

final class BlockingInputBuffer(blockSize: Int, in: InputBlockBuffer) extends InputBuffer {
  private val buf = new Array[Byte](blockSize)
  private var end: Int = 0
  private var off: Int = 0

  private def readBlock() {
    assert(off == end)
    end = in.readBlock(buf)
    off = 0
  }

  private def ensure(n: Int) {
    if (off == end)
      readBlock()
    assert(off + n <= end)
  }

  def close() {
    in.close()
  }

  def seek(offset: Long): Unit = {
    in.seek(offset)
    off = end
    readBlock()
    off = (offset & 0xFFFF).asInstanceOf[Int]
    assert(off <= end)
  }

  def readByte(): Byte = {
    ensure(1)
    val b = Memory.loadByte(buf, off)
    off += 1
    b
  }

  def readInt(): Int = {
    ensure(4)
    val i = Memory.loadInt(buf, off)
    off += 4
    i
  }

  def readLong(): Long = {
    ensure(8)
    val l = Memory.loadLong(buf, off)
    off += 8
    l
  }

  def readFloat(): Float = {
    ensure(4)
    val f = Memory.loadFloat(buf, off)
    off += 4
    f
  }

  def readDouble(): Double = {
    ensure(8)
    val d = Memory.loadDouble(buf, off)
    off += 8
    d
  }

  def readBytes(toRegion: Region, toOff0: Long, n0: Int) {
    assert(n0 >= 0)
    var toOff = toOff0
    var n = n0

    while (n > 0) {
      if (end == off)
        readBlock()
      val p = math.min(end - off, n)
      assert(p > 0)
      Region.storeBytes(toOff, buf, off, p)
      toOff += p
      n -= p
      off += p
    }
  }

  def skipByte() {
    ensure(1)
    off += 1
  }

  def skipInt() {
    ensure(4)
    off += 4
  }

  def skipLong() {
    ensure(8)
    off += 8
  }

  def skipFloat() {
    ensure(4)
    off += 4
  }

  def skipDouble() {
    ensure(8)
    off += 8
  }

  def skipBytes(n0: Int) {
    var n = n0
    while (n > 0) {
      if (end == off)
        readBlock()
      val p = math.min(end - off, n)
      n -= p
      off += p
    }
  }

  def readDoubles(to: Array[Double], toOff0: Int, n0: Int) {
    assert(toOff0 >= 0)
    assert(n0 >= 0)
    assert(toOff0 <= to.length - n0)
    var toOff = toOff0
    var n = n0

    while (n > 0) {
      if (end == off)
        readBlock()
      val p = math.min(end - off, n << 3) >>> 3
      assert(p > 0)
      Memory.memcpy(to, toOff, buf, off, p)
      toOff += p
      n -= p
      off += (p << 3)
    }
  }
}

final class StreamBlockInputBuffer(in: InputStream) extends InputBlockBuffer {
  private val lenBuf = new Array[Byte](4)

  def close() {
    in.close()
  }

  // this takes a virtual offset and will seek the underlying stream to offset >> 16
  def seek(offset: Long): Unit = in.asInstanceOf[ByteTrackingInputStream].seek(offset >> 16)

  def readBlock(buf: Array[Byte]): Int = {
    in.readFully(lenBuf, 0, 4)
    val len = Memory.loadInt(lenBuf, 0)
    assert(len >= 0)
    assert(len <= buf.length)
    in.readFully(buf, 0, len)
    len
  }
}

final class LZ4InputBlockBuffer(lz4: LZ4, blockSize: Int, in: InputBlockBuffer) extends InputBlockBuffer {
  private val comp = new Array[Byte](4 + lz4.maxCompressedLength(blockSize))
  private var decompBuf = new Array[Byte](blockSize)
  private var lim = 0

  def close() {
    in.close()
  }

  def seek(offset: Long): Unit = in.seek(offset)

  def readBlock(buf: Array[Byte]): Int = {
    val blockLen = in.readBlock(comp)
    val result = if (blockLen == -1) {
      -1
    } else {
      val compLen = blockLen - 4
      val decompLen = Memory.loadInt(comp, 0)
      lz4.decompress(buf, 0, decompLen, comp, 4, compLen)
      decompLen
    }
    lim = result
    result
  }
}
