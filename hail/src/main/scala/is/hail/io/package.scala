package is.hail

import java.io.OutputStreamWriter
import java.nio.charset._

import is.hail.types.virtual.Type
import is.hail.utils._
import is.hail.io.fs.FS

package object io {
  type VCFFieldAttributes = Map[String, String]
  type VCFAttributes = Map[String, VCFFieldAttributes]
  type VCFMetadata = Map[String, VCFAttributes]

  val utfCharset = Charset.forName("UTF-8")

  def exportTypes(filename: String, fs: FS, info: Array[(String, Type)]) {
    val sb = new StringBuilder
    using(new OutputStreamWriter(fs.create(filename))) { out =>
      info.foreachBetween { case (name, t) =>
        sb.append(prettyIdentifier(name))
        sb.append(":")
        t.pretty(sb, 0, compact = true)
      } { sb += ',' }

      out.write(sb.result())
    }
  }
}
