package is.hail.utils

import scala.reflect.ClassTag

object ArrayBuilder {
  final val defaultInitialCapacity: Int = 16
}

final class ArrayBuilder[@specialized T](initialCapacity: Int)(implicit tct: ClassTag[T]) extends Serializable {
  private[utils] var b: Array[T] = new Array[T](initialCapacity)
  private[utils] var size_ : Int = 0

  def this()(implicit tct: ClassTag[T]) = this(ArrayBuilder.defaultInitialCapacity)

  def size: Int = size_

  def length: Int = size_

  def isEmpty: Boolean = size_ == 0

  def apply(i: Int): T = {
    require(i >= 0 && i < size)
    b(i)
  }

  def update(i: Int, x: T) {
    require(i >= 0 && i < size)
    b(i) = x
  }

  def ensureCapacity(n: Int) {
    if (b.length < n) {
      val newCapacity = (b.length * 2).max(n)
      val newb = new Array[T](newCapacity)
      Array.copy(b, 0, newb, 0, size_)
      b = newb
    }
  }

  def clear(): Unit = {
    size_ = 0
  }

  def +=(x: T) {
    ensureCapacity(size_ + 1)
    b(size_) = x
    size_ += 1
  }

  def ++=(s: Seq[T]): Unit = s.foreach(x => this += x)

  def ++=(a: Array[T]): Unit = ++=(a, a.length)

  def ++=(a: Array[T], length: Int) {
    require(length >= 0 && length <= a.length)
    ensureCapacity(size_ + length)
    System.arraycopy(a, 0, b, size_, length)
    size_ += length
  }

  def result(): Array[T] = {
    val r = new Array[T](size_)
    Array.copy(b, 0, r, 0, size_)
    r
  }

  def underlying(): Array[T] = b

  def last: T = {
    assert(size_ > 0)
    b(size_ - 1)
  }

  def pop(): T = {
    size_ -= 1
    b(size)
  }

  def appendFrom(ab2: ArrayBuilder[T]): Unit = {
    ensureCapacity(size_ + ab2.size_)
    System.arraycopy(ab2.b, 0, b, size_, ab2.size_)
    size_ = size_ + ab2.size_
  }

  def setSizeUninitialized(size: Int): Unit = {
    ensureCapacity(size)
    size_ = size
  }

  override def clone(): ArrayBuilder[T] = {
    val ab = new ArrayBuilder[T]()
    ab.b = b.clone()
    ab.size_ = size_
    ab
  }
}
