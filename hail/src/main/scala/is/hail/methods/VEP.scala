package is.hail.methods

import java.io.BufferedInputStream

import com.fasterxml.jackson.core.JsonParseException
import is.hail.HailContext
import is.hail.annotations._
import is.hail.expr._
import is.hail.expr.ir.{ExecuteContext, TableValue}
import is.hail.expr.ir.functions.{RelationalFunctions, TableToTableFunction}
import is.hail.types._
import is.hail.types.physical.{PStruct, PType}
import is.hail.types.virtual._
import is.hail.methods.VEP._
import is.hail.rvd.RVD
import is.hail.sparkextras.ContextRDD
import is.hail.utils._
import is.hail.variant.{Locus, RegionValueVariant, VariantMethods}
import is.hail.io.fs.FS
import org.apache.spark.sql.Row
import org.json4s.{DefaultFormats, Extraction, Formats, JValue}
import org.json4s.jackson.JsonMethods

import scala.collection.JavaConverters._
import scala.collection.mutable

case class VEPConfiguration(
  command: Array[String],
  env: Map[String, String],
  vep_json_schema: TStruct)

object VEP {
  def readConfiguration(fs: FS, path: String): VEPConfiguration = {
    val jv = using(fs.open(path)) { in =>
      JsonMethods.parse(in)
    }
    implicit val formats: Formats = defaultJSONFormats + new TStructSerializer
    jv.extract[VEPConfiguration]
  }

  def printContext(w: (String) => Unit) {
    w("##fileformat=VCFv4.1")
    w("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT")
  }

  def printElement(w: (String) => Unit, v: (Locus, IndexedSeq[String])) {
    val (locus, alleles) = v

    val sb = new StringBuilder()
    sb.append(locus.contig)
    sb += '\t'
    sb.append(locus.position)
    sb.append("\t.\t")
    sb.append(alleles(0))
    sb += '\t'
    sb.append(alleles.tail.filter(_ != "*").mkString(","))
    sb.append("\t.\t.\tGT")
    w(sb.result())
  }

  def variantFromInput(input: String): (Locus, IndexedSeq[String]) = {
    try {
      val a = input.split("\t")
      (Locus(a(0), a(1).toInt), a(3) +: a(4).split(","))
    } catch {
      case e: Throwable => fatal(s"VEP returned invalid variant '$input'", e)
    }
  }

  def waitFor(proc: Process, err: StringBuilder, cmd: Array[String]): Unit = {
    val rc = proc.waitFor()

    if (rc != 0) {
      fatal(s"VEP command '${ cmd.mkString(" ") }' failed with non-zero exit status $rc\n" +
        "  VEP Error output:\n" + err.toString)
    }
  }

  def getCSQHeaderDefinition(cmd: Array[String], confEnv: Map[String, String]): Option[String] = {
    val csqHeaderRegex = "ID=CSQ[^>]+Description=\"([^\"]+)".r
    val pb = new ProcessBuilder(cmd.toList.asJava)
    val env = pb.environment()
    confEnv.foreach { case (key, value) => env.put(key, value) }

    val (jt, err, proc) = List((Locus("1", 13372), FastIndexedSeq("G", "C"))).iterator.pipe(pb,
      printContext,
      printElement,
      _ => ())

    val csqHeader = jt.flatMap(s => csqHeaderRegex.findFirstMatchIn(s).map(m => m.group(1)))
    waitFor(proc, err, cmd)

    if (csqHeader.hasNext)
      Some(csqHeader.next())
    else {
      warn("could not get VEP CSQ header")
      None
    }
  }

  def apply(fs: FS, params: VEPParameters): VEP = {
    val conf = VEP.readConfiguration(fs, params.config)
    new VEP(params, conf)
  }

  def apply(fs: FS, config: String, csq: Boolean, blockSize: Int): VEP =
    VEP(fs, VEPParameters(config, csq, blockSize))

  def fromJValue(fs: FS, jv: JValue): VEP = {
    println(jv)
    implicit val formats: Formats = RelationalFunctions.formats
    val params = jv.extract[VEPParameters]
    VEP(fs, params)
  }
}

case class VEPParameters(config: String, csq: Boolean, blockSize: Int)

class VEP(val params: VEPParameters, conf: VEPConfiguration) extends TableToTableFunction {
  private def vepSignature = conf.vep_json_schema

  override def preservesPartitionCounts: Boolean = false

  override def typ(childType: TableType): TableType = {
    val vepType = if (params.csq) TArray(TString) else vepSignature
    TableType(childType.rowType ++ TStruct("vep" -> vepType), childType.key, childType.globalType)
  }

  override def execute(ctx: ExecuteContext, tv: TableValue): TableValue = {
    assert(tv.typ.key == FastIndexedSeq("locus", "alleles"))
    assert(tv.typ.rowType.size == 2)

    val localConf = conf
    val localVepSignature = vepSignature

    val csq = params.csq
    val cmd = localConf.command.map(s =>
      if (s == "__OUTPUT_FORMAT_FLAG__")
        if (csq) "--vcf" else "--json"
      else
        s)

    val csqHeader = if (csq) getCSQHeaderDefinition(cmd, localConf.env) else None

    val inputQuery = localVepSignature.query("input")

    val csqRegex = "CSQ=[^;^\\t]+".r

    val localBlockSize = params.blockSize

    val localRowType = tv.rvd.rowPType
    val rowKeyOrd = tv.typ.keyType.ordering

    val prev = tv.rvd
    val annotations = prev
      .mapPartitions { (_, it) =>
        val pb = new ProcessBuilder(cmd.toList.asJava)
        val env = pb.environment()
        localConf.env.foreach { case (key, value) =>
          env.put(key, value)
        }

        val warnContext = new mutable.HashSet[String]

        val rvv = new RegionValueVariant(localRowType)
        it
          .map { ptr =>
            rvv.set(ptr)
            (rvv.locus(), rvv.alleles(): IndexedSeq[String])
          }
          .grouped(localBlockSize)
          .flatMap { block =>
            val (jt, err, proc) = block.iterator.pipe(pb,
              printContext,
              printElement,
              _ => ())

            val nonStarToOriginalVariant = block.map { case v@(locus, alleles) =>
              (locus, alleles.filter(_ != "*")) -> v
            }.toMap

            val kt = jt
              .filter(s => !s.isEmpty && s(0) != '#')
              .flatMap { s =>
                if (csq) {
                  val vepv@(vepLocus, vepAlleles) = variantFromInput(s)
                  nonStarToOriginalVariant.get(vepv) match {
                    case Some(v@(locus, alleles)) =>
                      val x = csqRegex.findFirstIn(s)
                      val a = x match {
                        case Some(value) =>
                          value.substring(4).split(",").toFastIndexedSeq
                        case None =>
                          warn(s"No CSQ INFO field for VEP output variant ${ VariantMethods.locusAllelesToString(vepLocus, vepAlleles) }.\nVEP output: $s.")
                          null
                      }
                      Some((Annotation(locus, alleles), a))
                    case None =>
                      fatal(s"VEP output variant ${ VariantMethods.locusAllelesToString(vepLocus, vepAlleles) } not found in original variants.\nVEP output: $s")
                  }
                } else {
                  try {
                    val jv = JsonMethods.parse(s)
                    val a = JSONAnnotationImpex.importAnnotation(jv, localVepSignature, warnContext = warnContext)
                    val variantString = inputQuery(a).asInstanceOf[String]
                    if (variantString == null)
                      fatal(s"VEP generated null variant string" +
                        s"\n  json:   $s" +
                        s"\n  parsed: $a")
                    val vepv@(vepLocus, vepAlleles) = variantFromInput(variantString)

                    nonStarToOriginalVariant.get(vepv) match {
                      case Some(v@(locus, alleles)) =>
                        Some((Annotation(locus, alleles), a))
                      case None =>
                        fatal(s"VEP output variant ${ VariantMethods.locusAllelesToString(vepLocus, vepAlleles) } not found in original variants.\nVEP output: $s")
                    }
                  } catch {
                    case e: JsonParseException =>
                      log.warn(s"VEP failed to produce parsable JSON!\n  json: $s\n  error: $e")
                      None
                  }
                }
              }

            val r = kt.toArray
              .sortBy(_._1)(rowKeyOrd.toOrdering)

            waitFor(proc, err, cmd)

            r
          }
      }

    val vepType: Type = if (params.csq) TArray(TString) else vepSignature

    val vepRVDType = prev.typ.copy(rowType = prev.rowPType.appendKey("vep", PType.canonical(vepType)))

    val vepRowType = vepRVDType.rowType

    val vepRVD: RVD = RVD(
      vepRVDType,
      prev.partitioner,
      ContextRDD.weaken(annotations).cmapPartitions { (ctx, it) =>
        val rvb = ctx.rvb

        it.map { case (v, vep) =>
          rvb.start(vepRowType)
          rvb.startStruct()
          rvb.addAnnotation(vepRowType.types(0).virtualType, v.asInstanceOf[Row].get(0))
          rvb.addAnnotation(vepRowType.types(1).virtualType, v.asInstanceOf[Row].get(1))
          rvb.addAnnotation(vepRowType.types(2).virtualType, vep)
          rvb.endStruct()

          rvb.end()
        }
      })

    val (globalValue, globalType) =
      if (params.csq)
        (Row(csqHeader.getOrElse("")), TStruct("vep_csq_header" -> TString))
      else
        (Row(), TStruct.empty)

    TableValue(ctx,
      TableType(vepRowType.virtualType, FastIndexedSeq("locus", "alleles"), globalType),
      BroadcastRow(ctx, globalValue, globalType),
      vepRVD)
  }

  override def toJValue: JValue = {
    decomposeWithName(params, "VEP")(RelationalFunctions.formats)
  }

  override def hashCode(): Int = params.hashCode()

  override def equals(that: Any): Boolean = that match {
    case that: VEP => params == that.params
    case _ => false
  }
}
