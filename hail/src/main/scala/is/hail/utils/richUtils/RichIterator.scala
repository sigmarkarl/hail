package is.hail.utils.richUtils

import java.io.PrintWriter

import is.hail.annotations.{Region, RegionValue, RegionValueBuilder}
import is.hail.expr.types.physical.PStruct
import is.hail.expr.types.virtual.TStruct
import is.hail.rvd.RVDContext

import scala.collection.JavaConverters._
import scala.io.Source
import is.hail.utils.{FlipbookIterator, StagingIterator, StateMachine}
import org.apache.spark.sql.Row

import scala.reflect.ClassTag

class RichIteratorLong(val it: Iterator[Long]) extends AnyVal {
  def toIteratorRV(region: Region): Iterator[RegionValue] = {
    val rv = RegionValue(region)
    it.map(ptr => { rv.setOffset(ptr); rv })
  }
}

class RichIterator[T](val it: Iterator[T]) extends AnyVal {
  def toStagingIterator: StagingIterator[T] = {
    val bit = it.buffered
    StagingIterator(
      new StateMachine[T] {
        def value: T = bit.head
        def isValid = bit.hasNext
        def advance() { bit.next() }
      }
    )
  }

  def toFlipbookIterator: FlipbookIterator[T] = toStagingIterator

  def foreachBetween(f: (T) => Unit)(g: => Unit) {
    if (it.hasNext) {
      f(it.next())
      while (it.hasNext) {
        g
        f(it.next())
      }
    }
  }

  def pipe(pb: ProcessBuilder,
    printHeader: (String => Unit) => Unit,
    printElement: (String => Unit, T) => Unit,
    printFooter: (String => Unit) => Unit): (Iterator[String], StringBuilder, Process) = {

    val command = pb.command().asScala.mkString(" ")

    val proc = pb.start()

    val error = new StringBuilder()
    // Start a thread capture the process stderr
    new Thread("stderr reader for " + command) {
      override def run() {
        Source.fromInputStream(proc.getErrorStream).addString(error)
      }
    }.start()

    // Start a thread to feed the process input from our parent's iterator
    new Thread("stdin writer for " + command) {
      override def run() {
        val out = new PrintWriter(proc.getOutputStream)

        printHeader(out.println)
        it.foreach(x => printElement(out.println, x))
        printFooter(out.println)
        out.close()
      }
    }.start()

    // Return an iterator that reads lines from the process's stdout,
    // a StringBuilder that captures standard error that should not be read
    // from or written to until waiting for the process, and the process itself
    (Source.fromInputStream(proc.getInputStream).getLines(), error, proc)
  }

  def trueGroupedIterator(groupSize: Int): Iterator[Iterator[T]] =
    new Iterator[Iterator[T]] {
      var prev: Iterator[T] = null

      def hasNext: Boolean = it.hasNext

      def next(): Iterator[T] = {
        if (!hasNext)
          throw new NoSuchElementException("next on empty iterator")

        // the previous element must must be fully consumed or the next block will start in the wrong place
        assert(prev == null || !prev.hasNext)
        prev = new Iterator[T] {
          var i = 0

          def hasNext: Boolean = it.hasNext && i < groupSize

          def next(): T = {
            if (!hasNext)
              throw new NoSuchElementException("next on empty iterator")
            i += 1
            it.next()
          }
        }

        prev
      }
    }

  def toFastSeq(implicit tct: ClassTag[T]): Seq[T] = toFastIndexedSeq

  def toFastIndexedSeq(implicit tct: ClassTag[T]): IndexedSeq[T] = it.toArray[T]

  def headOption: Option[T] = if (it.isEmpty) None else Some(it.next())
}

class RichRowIterator(val it: Iterator[Row]) extends AnyVal {
  def copyToRegion(region: Region, rowTyp: PStruct): Iterator[Long] = {
    val rvb = new RegionValueBuilder(region)
    it.map { row =>
      rvb.start(rowTyp)
      rvb.addAnnotation(rowTyp.virtualType, row)
      rvb.end()
    }
  }
}
