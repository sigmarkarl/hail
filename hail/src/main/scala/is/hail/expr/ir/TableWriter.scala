package is.hail.expr.ir

import is.hail.GenericIndexedSeqSerializer
import is.hail.utils.ExportType
import org.json4s.{DefaultFormats, Formats, ShortTypeHints}

object TableWriter {
  implicit val formats: Formats = new DefaultFormats() {
    override val typeHints = ShortTypeHints(
      List(classOf[TableNativeWriter], classOf[TableTextWriter]))
    override val typeHintFieldName = "name"
  }
}

abstract class TableWriter {
  def path: String
  def apply(ctx: ExecuteContext, mv: TableValue): Unit
}

case class TableNativeWriter(
  path: String,
  overwrite: Boolean = true,
  stageLocally: Boolean = false,
  codecSpecJSONStr: String = null
) extends TableWriter {
  def apply(ctx: ExecuteContext, tv: TableValue): Unit = tv.write(ctx, path, overwrite, stageLocally, codecSpecJSONStr)
}

case class TableTextWriter(
  path: String,
  typesFile: String = null,
  header: Boolean = true,
  exportType: String = ExportType.CONCATENATED,
  delimiter: String
) extends TableWriter {
  def apply(ctx: ExecuteContext, tv: TableValue): Unit = tv.export(ctx, path, typesFile, header, exportType, delimiter)
}

object WrappedMatrixNativeMultiWriter {
  implicit val formats: Formats = MatrixNativeMultiWriter.formats +
    ShortTypeHints(List(classOf[WrappedMatrixNativeMultiWriter])) +
    GenericIndexedSeqSerializer
}

case class WrappedMatrixNativeMultiWriter(
  writer: MatrixNativeMultiWriter,
  colKey: IndexedSeq[String]
) {
  def apply(ctx: ExecuteContext, mvs: IndexedSeq[TableValue]): Unit = writer.apply(
    ctx, mvs.map(_.toMatrixValue(colKey)))
}
