package is.hail.expr.ir

import is.hail.HailContext
import is.hail.expr.ir.functions.RelationalFunctions
import is.hail.expr.types.physical.PType
import is.hail.expr.types.virtual._
import is.hail.expr.types.{MatrixType, TableType}
import is.hail.expr.{JSONAnnotationImpex, Nat, ParserUtils}
import is.hail.io.bgen.MatrixBGENReaderSerializer
import is.hail.io.{BufferSpec, AbstractTypedCodecSpec}
import is.hail.rvd.{AbstractRVDSpec, RVDType}
import is.hail.utils.StringEscapeUtils._
import is.hail.utils._
import is.hail.variant.ReferenceGenome
import org.json4s.Formats
import org.json4s.jackson.{JsonMethods, Serialization}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.parsing.combinator.JavaTokenParsers
import scala.util.parsing.input.Positional

abstract class Token extends Positional {
  def value: Any

  def getName: String
}

final case class IdentifierToken(value: String) extends Token {
  def getName: String = "identifier"
}

final case class StringToken(value: String) extends Token {
  def getName: String = "string"
}

final case class IntegerToken(value: Long) extends Token {
  def getName: String = "integer"
}

final case class FloatToken(value: Double) extends Token {
  def getName: String = "float"
}

final case class PunctuationToken(value: String) extends Token {
  def getName: String = "punctuation"
}

object IRLexer extends JavaTokenParsers {
  val token: Parser[Token] =
    identifier ^^ { id => IdentifierToken(id) } |
      float64_literal ^^ { d => FloatToken(d) } |
      int64_literal ^^ { l => IntegerToken(l) } |
      string_literal ^^ { s => StringToken(s) } |
      "[()\\[\\]{}<>,:+@=]".r ^^ { p => PunctuationToken(p) }

  val lexer: Parser[Array[Token]] = rep(positioned(token)) ^^ { l => l.toArray }

  def quotedLiteral(delim: Char, what: String): Parser[String] =
    new Parser[String] {
      def apply(in: Input): ParseResult[String] = {
        var r = in

        val source = in.source
        val offset = in.offset
        val start = handleWhiteSpace(source, offset)
        r = r.drop(start - offset)

        if (r.atEnd || r.first != delim)
          return Failure(s"consumed $what", r)
        r = r.rest

        val sb = new StringBuilder()

        val escapeChars = "\\bfnrtu'\"`".toSet
        var continue = true
        while (continue) {
          if (r.atEnd)
            return Failure(s"unterminated $what", r)
          val c = r.first
          r = r.rest
          if (c == delim)
            continue = false
          else {
            sb += c
            if (c == '\\') {
              if (r.atEnd)
                return Failure(s"unterminated $what", r)
              val d = r.first
              if (!escapeChars.contains(d))
                return Failure(s"invalid escape character in $what", r)
              sb += d
              r = r.rest
            }
          }
        }
        Success(unescapeString(sb.result()), r)
      }
    }

  override def stringLiteral: Parser[String] =
    quotedLiteral('"', "string literal") | quotedLiteral('\'', "string literal")

  def backtickLiteral: Parser[String] = quotedLiteral('`', "backtick identifier")

  def identifier = backtickLiteral | ident

  def string_literal: Parser[String] = stringLiteral

  def int64_literal: Parser[Long] = wholeNumber.map(_.toLong)

  def float64_literal: Parser[Double] =
      "-inf" ^^ { _ => Double.NegativeInfinity } | // inf, neginf, and nan are parsed as identifiers
      """[+-]?\d+(\.\d+)?[eE][+-]?\d+""".r ^^ { _.toDouble } |
      """[+-]?\d*\.\d+""".r ^^ { _.toDouble }

  def parse(code: String): Array[Token] = {
    parseAll(lexer, code) match {
      case Success(result, _) => result
      case NoSuccess(msg, next) => ParserUtils.error(next.pos, msg)
    }
  }
}

object TypeParserEnvironment {
  def empty: TypeParserEnvironment = TypeParserEnvironment(Map.empty)

  // FIXME: This will go away when references are no longer kept on global object
  def default: TypeParserEnvironment = TypeParserEnvironment(ReferenceGenome.references)
}

case class TypeParserEnvironment(
  rgMap: Map[String, ReferenceGenome]
) {
  def getReferenceGenome(name: String): ReferenceGenome = rgMap(name)

}

case class IRParserEnvironment(
  refMap: Map[String, Type] = Map.empty,
  irMap: Map[String, BaseIR] = Map.empty,
  typEnv: TypeParserEnvironment = TypeParserEnvironment.default
) {
  def update(newRefMap: Map[String, Type] = Map.empty, newIRMap: Map[String, BaseIR] = Map.empty): IRParserEnvironment =
    copy(refMap = refMap ++ newRefMap, irMap = irMap ++ newIRMap)

  def withRefMap(newRefMap: Map[String, Type]): IRParserEnvironment = {
    assert(refMap.isEmpty || newRefMap.isEmpty)
    copy(refMap = newRefMap)
  }

  def +(t: (String, Type)): IRParserEnvironment = copy(refMap = refMap + t, irMap)
}

object IRParser {
  def error(t: Token, msg: String): Nothing = ParserUtils.error(t.pos, msg)

  def deserialize[T](str: String)(implicit formats: Formats, mf: Manifest[T]): T = {
    Serialization.read[T](str)
  }

  def consumeToken(it: TokenIterator): Token = {
    if (!it.hasNext)
      fatal("No more tokens to consume.")
    it.next()
  }

  def punctuation(it: TokenIterator, symbol: String): String = {
    consumeToken(it) match {
      case x: PunctuationToken if x.value == symbol => x.value
      case x: Token => error(x, s"Expected punctuation '$symbol' but found ${ x.getName } '${ x.value }'.")
    }
  }

  def identifier(it: TokenIterator): String = {
    consumeToken(it) match {
      case x: IdentifierToken => x.value
      case x: Token => error(x, s"Expected identifier but found ${ x.getName } '${ x.value }'.")
    }
  }

  def identifier(it: TokenIterator, expectedId: String): String = {
    consumeToken(it) match {
      case x: IdentifierToken if x.value == expectedId => x.value
      case x: Token => error(x, s"Expected identifier '$expectedId' but found ${ x.getName } '${ x.value }'.")
    }
  }

  def identifiers(it: TokenIterator): Array[String] = {
    punctuation(it, "(")
    val ids = repUntil(it, identifier, PunctuationToken(")"))
    punctuation(it, ")")
    ids
  }

  def boolean_literal(it: TokenIterator): Boolean = {
    consumeToken(it) match {
      case IdentifierToken("True") => true
      case IdentifierToken("False") => false
      case x: Token => error(x, s"Expected boolean but found ${ x.getName } '${ x.value }'.")
    }
  }

  def int32_literal(it: TokenIterator): Int = {
    consumeToken(it) match {
      case x: IntegerToken =>
        if (x.value >= Int.MinValue && x.value <= Int.MaxValue)
          x.value.toInt
        else
          error(x, s"Found integer '${ x.value }' that is outside the numeric range for int32.")
      case x: Token => error(x, s"Expected integer but found ${ x.getName } '${ x.value }'.")
    }
  }

  def int64_literal(it: TokenIterator): Long = {
    consumeToken(it) match {
      case x: IntegerToken => x.value
      case x: Token => error(x, s"Expected integer but found ${ x.getName } '${ x.value }'.")
    }
  }

  def float32_literal(it: TokenIterator): Float = {
    consumeToken(it) match {
      case x: FloatToken =>
        if (x.value >= Float.MinValue && x.value <= Float.MaxValue)
          x.value.toFloat
        else
          error(x, s"Found float '${ x.value }' that is outside the numeric range for float32.")
      case x: IntegerToken => x.value.toFloat
      case x: IdentifierToken => x.value match {
        case "nan" => Float.NaN
        case "inf" => Float.PositiveInfinity
        case "neginf" => Float.NegativeInfinity
        case _ => error(x, s"Expected float but found ${ x.getName } '${ x.value }'.")
      }
      case x: Token => error(x, s"Expected float but found ${ x.getName } '${ x.value }'.")
    }
  }

  def float64_literal(it: TokenIterator): Double = {
    consumeToken(it) match {
      case x: FloatToken => x.value
      case x: IntegerToken => x.value.toDouble
      case x: IdentifierToken => x.value match {
        case "nan" => Double.NaN
        case "inf" => Double.PositiveInfinity
        case "neginf" => Double.NegativeInfinity
        case _ => error(x, s"Expected float but found ${ x.getName } '${ x.value }'.")
      }
      case x: Token => error(x, s"Expected float but found ${ x.getName } '${ x.value }'.")
    }
  }

  def string_literal(it: TokenIterator): String = {
    consumeToken(it) match {
      case x: StringToken => x.value
      case x: Token => error(x, s"Expected string but found ${ x.getName } '${ x.value }'.")
    }
  }

  def literals[T](literalIdentifier: TokenIterator => T)(it: TokenIterator)(implicit tct: ClassTag[T]): Array[T] = {
    punctuation(it, "(")
    val literals = repUntil(it, literalIdentifier, PunctuationToken(")"))
    punctuation(it, ")")
    literals
  }

  def string_literals: TokenIterator => Array[String] = literals(string_literal)
  def int32_literals: TokenIterator => Array[Int] = literals(int32_literal)
  def int64_literals: TokenIterator => Array[Long] = literals(int64_literal)

  def opt[T](it: TokenIterator, f: (TokenIterator) => T)(implicit tct: ClassTag[T]): Option[T] = {
    it.head match {
      case x: IdentifierToken if x.value == "None" =>
        consumeToken(it)
        None
      case _ =>
        Some(f(it))
    }
  }

  def repsepUntil[T](it: TokenIterator,
    f: (TokenIterator) => T,
    sep: Token,
    end: Token)(implicit tct: ClassTag[T]): Array[T] = {
    val xs = new ArrayBuilder[T]()
    while (it.hasNext && it.head != end) {
      xs += f(it)
      if (it.head == sep)
        consumeToken(it)
    }
    xs.result()
  }

  def repUntil[T](it: TokenIterator,
    f: (TokenIterator) => T,
    end: Token)(implicit tct: ClassTag[T]): Array[T] = {
    val xs = new ArrayBuilder[T]()
    while (it.hasNext && it.head != end) {
      xs += f(it)
    }
    xs.result()
  }

  def decorator(it: TokenIterator): (String, String) = {
    punctuation(it, "@")
    val name = identifier(it)
    punctuation(it, "=")
    val desc = string_literal(it)
    (name, desc)
  }

  def tuple_subset_field(env: TypeParserEnvironment)(it: TokenIterator): (Int, Type) = {
    val i = int32_literal(it)
    punctuation(it, ":")
    val t = type_expr(env)(it)
    i -> t
  }

  def struct_field[T](f: TokenIterator => T)(it: TokenIterator): (String, T) = {
    val name = identifier(it)
    punctuation(it, ":")
    val typ = f(it)
    while (it.hasNext && it.head == PunctuationToken("@")) {
      decorator(it)
    }
    (name, typ)
  }
  def type_field(env: TypeParserEnvironment)(it: TokenIterator): (String, Type) = {
    struct_field(type_expr(env))(it)
  }

  def type_exprs(env: TypeParserEnvironment)(it: TokenIterator): Array[Type] = {
    punctuation(it, "(")
    val types = repUntil(it, type_expr(env), PunctuationToken(")"))
    punctuation(it, ")")
    types
  }

  def type_expr(env: TypeParserEnvironment)(it: TokenIterator): Type = {
    val req = it.head match {
      case x: PunctuationToken if x.value == "+" =>
        consumeToken(it)
        true
      case _ => false
    }

    val typ = identifier(it) match {
      case "Interval" =>
        punctuation(it, "[")
        val pointType = type_expr(env)(it)
        punctuation(it, "]")
        TInterval(pointType, req)
      case "Boolean" => TBoolean(req)
      case "Int32" => TInt32(req)
      case "Int64" => TInt64(req)
      case "Int" => TInt32(req)
      case "Float32" => TFloat32(req)
      case "Float64" => TFloat64(req)
      case "String" => TString(req)
      case "Locus" =>
        punctuation(it, "(")
        val rg = identifier(it)
        punctuation(it, ")")
        env.getReferenceGenome(rg).locusType.setRequired(req)
      case "Call" => TCall(req)
      case "Stream" =>
        punctuation(it, "[")
        val elementType = type_expr(env)(it)
        punctuation(it, "]")
        TStream(elementType, req)
      case "Array" =>
        punctuation(it, "[")
        val elementType = type_expr(env)(it)
        punctuation(it, "]")
        TArray(elementType, req)
      case "NDArray" =>
        punctuation(it, "[")
        val elementType = type_expr(env)(it)
        punctuation(it, ",")
        val nDims = int32_literal(it)
        punctuation(it, "]")
        TNDArray(elementType, Nat(nDims), req)
      case "Set" =>
        punctuation(it, "[")
        val elementType = type_expr(env)(it)
        punctuation(it, "]")
        TSet(elementType, req)
      case "Dict" =>
        punctuation(it, "[")
        val keyType = type_expr(env)(it)
        punctuation(it, ",")
        val valueType = type_expr(env)(it)
        punctuation(it, "]")
        TDict(keyType, valueType, req)
      case "Tuple" =>
        punctuation(it, "[")
        val types = repsepUntil(it, type_expr(env), PunctuationToken(","), PunctuationToken("]"))
        punctuation(it, "]")
        TTuple(req, types: _*)
      case "TupleSubset" =>
        punctuation(it, "[")
        val fields = repsepUntil(it, tuple_subset_field(env), PunctuationToken(","), PunctuationToken("]"))
        punctuation(it, "]")
        TTuple(fields.map { case (idx, t) => TupleField(idx, t)}, req)
      case "Struct" =>
        punctuation(it, "{")
        val args = repsepUntil(it, type_field(env), PunctuationToken(","), PunctuationToken("}"))
        punctuation(it, "}")
        val fields = args.zipWithIndex.map { case ((id, t), i) => Field(id, t, i) }
        TStruct(fields, req)
      case "Union" =>
        punctuation(it, "{")
        val args = repsepUntil(it, type_field(env), PunctuationToken(","), PunctuationToken("}"))
        punctuation(it, "}")
        val cases = args.zipWithIndex.map { case ((id, t), i) => Case(id, t, i) }
        TUnion(cases, req)
    }
    assert(typ.required == req)
    typ
  }

  def keys(it: TokenIterator): Array[String] = {
    punctuation(it, "[")
    val keys = repsepUntil(it, identifier, PunctuationToken(","), PunctuationToken("]"))
    punctuation(it, "]")
    keys
  }

  def trailing_keys(it: TokenIterator): Array[String] = {
    it.head match {
      case x: PunctuationToken if x.value == "]" =>
        Array.empty[String]
      case x: PunctuationToken if x.value == "," =>
        punctuation(it, ",")
        repsepUntil(it, identifier, PunctuationToken(","), PunctuationToken("]"))
    }
  }

  def rvd_type_expr(env: TypeParserEnvironment)(it: TokenIterator): RVDType = {
    identifier(it) match {
      case "RVDType" | "OrderedRVDType" =>
        punctuation(it, "{")
        identifier(it, "key")
        punctuation(it, ":")
        punctuation(it, "[")
        val partitionKey = keys(it)
        val restKey = trailing_keys(it)
        punctuation(it, "]")
        punctuation(it, ",")
        identifier(it, "row")
        punctuation(it, ":")
        val rowType = coerce[TStruct](type_expr(env)(it))
        RVDType(rowType.physicalType, partitionKey ++ restKey)
    }
  }

  def table_type_expr(env: TypeParserEnvironment)(it: TokenIterator): TableType = {
    identifier(it, "Table")
    punctuation(it, "{")

    identifier(it, "global")
    punctuation(it, ":")
    val globalType = coerce[TStruct](type_expr(env)(it))
    punctuation(it, ",")

    identifier(it, "key")
    punctuation(it, ":")
    val key = opt(it, keys).getOrElse(Array.empty[String])
    punctuation(it, ",")

    identifier(it, "row")
    punctuation(it, ":")
    val rowType = coerce[TStruct](type_expr(env)(it))
    punctuation(it, "}")
    TableType(rowType, key.toFastIndexedSeq, coerce[TStruct](-globalType))
  }

  def matrix_type_expr(env: TypeParserEnvironment)(it: TokenIterator): MatrixType = {
    identifier(it, "Matrix")
    punctuation(it, "{")

    identifier(it, "global")
    punctuation(it, ":")
    val globalType = coerce[TStruct](type_expr(env)(it))
    punctuation(it, ",")

    identifier(it, "col_key")
    punctuation(it, ":")
    val colKey = keys(it)
    punctuation(it, ",")

    identifier(it, "col")
    punctuation(it, ":")
    val colType = coerce[TStruct](type_expr(env)(it))
    punctuation(it, ",")

    identifier(it, "row_key")
    punctuation(it, ":")
    punctuation(it, "[")
    val rowPartitionKey = keys(it)
    val rowRestKey = trailing_keys(it)
    punctuation(it, "]")
    punctuation(it, ",")

    identifier(it, "row")
    punctuation(it, ":")
    val rowType = coerce[TStruct](type_expr(env)(it))
    punctuation(it, ",")

    identifier(it, "entry")
    punctuation(it, ":")
    val entryType = coerce[TStruct](type_expr(env)(it))
    punctuation(it, "}")

    MatrixType(coerce[TStruct](-globalType), colKey, colType, rowPartitionKey ++ rowRestKey, rowType, entryType)
  }

  def agg_op(it: TokenIterator): AggOp =
    AggOp.fromString(identifier(it))

  def agg_signature(env: TypeParserEnvironment)(it: TokenIterator): AggSignature = {
    punctuation(it, "(")
    val op = agg_op(it)
    val ctorArgs = type_exprs(env)(it).map(t => -t)
    val initOpArgs = opt(it, type_exprs(env)).map(_.map(t => -t))
    val seqOpArgs = type_exprs(env)(it).map(t => -t)
    punctuation(it, ")")
    AggSignature(op, ctorArgs, initOpArgs.map(_.toFastIndexedSeq), seqOpArgs)
  }

  def agg_signature2(env: TypeParserEnvironment)(it: TokenIterator): AggSignature2 = {
    punctuation(it, "(")
    val op = agg_op(it)
    val initArgs = type_exprs(env)(it).map(t => -t)
    val seqOpArgs = type_exprs(env)(it).map(t => -t)
    val nested = opt(it, agg_signatures(env)).map(_.toFastSeq)
    punctuation(it, ")")
    AggSignature2(op, initArgs, seqOpArgs, nested)
  }

  def agg_signatures(env: TypeParserEnvironment)(it: TokenIterator): Array[AggSignature2] = {
    punctuation(it, "(")
    val sigs = repUntil(it, agg_signature2(env), PunctuationToken(")"))
    punctuation(it, ")")
    sigs
  }

  def ir_value(env: TypeParserEnvironment)(it: TokenIterator): (Type, Any) = {
    val typ = type_expr(env)(it)
    val s = string_literal(it)
    val vJSON = JsonMethods.parse(s)
    val v = JSONAnnotationImpex.importAnnotation(vJSON, typ)
    (typ, v)
  }

  def named_value_irs(env: IRParserEnvironment)(it: TokenIterator): Array[(String, IR)] =
    repUntil(it, named_value_ir(env), PunctuationToken(")"))

  def named_value_ir(env: IRParserEnvironment)(it: TokenIterator): (String, IR) = {
    punctuation(it, "(")
    val name = identifier(it)
    val value = ir_value_expr(env)(it)
    punctuation(it, ")")
    (name, value)
  }

  def ir_value_exprs(env: IRParserEnvironment)(it: TokenIterator): Array[IR] = {
    punctuation(it, "(")
    val irs = ir_value_children(env)(it)
    punctuation(it, ")")
    irs
  }

  def ir_value_children(env: IRParserEnvironment)(it: TokenIterator): Array[IR] =
    repUntil(it, ir_value_expr(env), PunctuationToken(")"))

  def ir_value_expr(env: IRParserEnvironment)(it: TokenIterator): IR = {
    punctuation(it, "(")
    val ir = ir_value_expr_1(env)(it)
    punctuation(it, ")")
    ir
  }

  def ir_value_expr_1(env: IRParserEnvironment)(it: TokenIterator): IR = {
    identifier(it) match {
      case "I32" => I32(int32_literal(it))
      case "I64" => I64(int64_literal(it))
      case "F32" => F32(float32_literal(it))
      case "F64" => F64(float64_literal(it))
      case "Str" => Str(string_literal(it))
      case "True" => True()
      case "False" => False()
      case "Literal" =>
        val (t, v) = ir_value(env.typEnv)(it)
        Literal.coerce(t, v)
      case "Void" => Void()
      case "Cast" =>
        val typ = type_expr(env.typEnv)(it)
        val v = ir_value_expr(env)(it)
        Cast(v, typ)
      case "CastRename" =>
        val typ = type_expr(env.typEnv)(it)
        val v = ir_value_expr(env)(it)
        CastRename(v, typ)
      case "NA" => NA(type_expr(env.typEnv)(it))
      case "IsNA" => IsNA(ir_value_expr(env)(it))
      case "Coalesce" =>
        val children = ir_value_children(env)(it)
        Coalesce.unify(children)
      case "If" =>
        val cond = ir_value_expr(env)(it)
        val consq = ir_value_expr(env)(it)
        val altr = ir_value_expr(env)(it)
        If.unify(cond, consq, altr)
      case "Let" =>
        val name = identifier(it)
        val value = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> value.typ))(it)
        Let(name, value, body)
      case "AggLet" =>
        val name = identifier(it)
        val isScan = boolean_literal(it)
        val value = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> value.typ))(it)
        AggLet(name, value, body, isScan)
      case "Ref" =>
        val id = identifier(it)
        Ref(id, env.refMap(id))
      case "RelationalRef" =>
        val id = identifier(it)
        val t = type_expr(env.typEnv)(it)
        RelationalRef(id, t)
      case "RelationalLet" =>
        val name = identifier(it)
        val value = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> value.typ))(it)
        RelationalLet(name, value, body)
      case "ApplyBinaryPrimOp" =>
        val op = BinaryOp.fromString(identifier(it))
        val l = ir_value_expr(env)(it)
        val r = ir_value_expr(env)(it)
        ApplyBinaryPrimOp(op, l, r)
      case "ApplyUnaryPrimOp" =>
        val op = UnaryOp.fromString(identifier(it))
        val x = ir_value_expr(env)(it)
        ApplyUnaryPrimOp(op, x)
      case "ApplyComparisonOp" =>
        val opName = identifier(it)
        val l = ir_value_expr(env)(it)
        val r = ir_value_expr(env)(it)
        val op = ComparisonOp.fromStringAndTypes((opName, l.typ, r.typ))
        ApplyComparisonOp(op, l, r)
      case "MakeArray" =>
        val typ = opt(it, type_expr(env.typEnv)).map(_.asInstanceOf[TArray]).orNull
        val args = ir_value_children(env)(it)
        MakeArray.unify(args, typ)
      case "MakeStream" =>
        val typ = opt(it, type_expr(env.typEnv)).map(_.asInstanceOf[TStream]).orNull
        val args = ir_value_children(env)(it)
        MakeStream(args, typ)
      case "ArrayRef" =>
        val a = ir_value_expr(env)(it)
        val i = ir_value_expr(env)(it)
        ArrayRef(a, i)
      case "ArrayLen" => ArrayLen(ir_value_expr(env)(it))
      case "ArrayRange" =>
        val start = ir_value_expr(env)(it)
        val stop = ir_value_expr(env)(it)
        val step = ir_value_expr(env)(it)
        ArrayRange(start, stop, step)
      case "StreamRange" =>
        val start = ir_value_expr(env)(it)
        val stop = ir_value_expr(env)(it)
        val step = ir_value_expr(env)(it)
        StreamRange(start, stop, step)
      case "ArraySort" =>
        val l = identifier(it)
        val r = identifier(it)
        val a = ir_value_expr(env)(it)
        val elt = coerce[TStreamable](a.typ).elementType
        val body = ir_value_expr(env + (l -> elt) + (r -> elt))(it)
        ArraySort(a, l, r, body)
      case "MakeNDArray" =>
        val data = ir_value_expr(env)(it)
        val shape = ir_value_expr(env)(it)
        val rowMajor = ir_value_expr(env)(it)
        MakeNDArray(data, shape, rowMajor)
      case "NDArrayShape" =>
        val nd = ir_value_expr(env)(it)
        NDArrayShape(nd)
      case "NDArrayReshape" =>
        val nd = ir_value_expr(env)(it)
        val shape = ir_value_expr(env)(it)
        NDArrayReshape(nd, shape)
      case "NDArrayMap" =>
        val name = identifier(it)
        val nd = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> -coerce[TNDArray](nd.typ).elementType))(it)
        NDArrayMap(nd, name, body)
      case "NDArrayMap2" =>
        val lName = identifier(it)
        val rName = identifier(it)
        val l = ir_value_expr(env)(it)
        val r = ir_value_expr(env)(it)
        val body_env = (env + (lName -> -(coerce[TNDArray](l.typ).elementType))
                            + (rName -> -(coerce[TNDArray](r.typ).elementType)))
        val body = ir_value_expr(body_env)(it)
        NDArrayMap2(l, r, lName, rName, body)
      case "NDArrayReindex" =>
        val indexExpr = int32_literals(it)
        val nd = ir_value_expr(env)(it)
        NDArrayReindex(nd, indexExpr)
      case "NDArrayAgg" =>
        val axes = int32_literals(it)
        val nd = ir_value_expr(env)(it)
        NDArrayAgg(nd, axes)
      case "NDArrayRef" =>
        val nd = ir_value_expr(env)(it)
        val idxs = ir_value_children(env)(it)
        NDArrayRef(nd, idxs)
      case "NDArraySlice" =>
        val nd = ir_value_expr(env)(it)
        val slices = ir_value_expr(env)(it)
        NDArraySlice(nd, slices)
      case "NDArrayMatMul" =>
        val l = ir_value_expr(env)(it)
        val r = ir_value_expr(env)(it)
        NDArrayMatMul(l, r)
      case "NDArrayWrite" =>
        val nd = ir_value_expr(env)(it)
        val path = ir_value_expr(env)(it)
        NDArrayWrite(nd, path)
      case "ToSet" => ToSet(ir_value_expr(env)(it))
      case "ToDict" => ToDict(ir_value_expr(env)(it))
      case "ToArray" => ToArray(ir_value_expr(env)(it))
      case "ToStream" => ToStream(ir_value_expr(env)(it))
      case "LowerBoundOnOrderedCollection" =>
        val onKey = boolean_literal(it)
        val col = ir_value_expr(env)(it)
        val elem = ir_value_expr(env)(it)
        LowerBoundOnOrderedCollection(col, elem, onKey)
      case "GroupByKey" =>
        val col = ir_value_expr(env)(it)
        GroupByKey(col)
      case "ArrayMap" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> -coerce[TStreamable](a.typ).elementType))(it)
        ArrayMap(a, name, body)
      case "ArrayFilter" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> -coerce[TStreamable](a.typ).elementType))(it)
        ArrayFilter(a, name, body)
      case "ArrayFlatMap" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> -coerce[TStreamable](a.typ).elementType))(it)
        ArrayFlatMap(a, name, body)
      case "ArrayFold" =>
        val accumName = identifier(it)
        val valueName = identifier(it)
        val a = ir_value_expr(env)(it)
        val zero = ir_value_expr(env)(it)
        val eltType = -coerce[TStreamable](a.typ).elementType
        val body = ir_value_expr(env.update(Map(accumName -> zero.typ, valueName -> eltType)))(it)
        ArrayFold(a, zero, accumName, valueName, body)
      case "ArrayFold2" =>
        val accumNames = identifiers(it)
        val valueName = identifier(it)
        val a = ir_value_expr(env)(it)
        val accs = accumNames.map(name => (name, ir_value_expr(env)(it)))
        val eltType = -coerce[TStreamable](a.typ).elementType
        val resultEnv = env.update(accs.map { case (name, value) => (name, value.typ) }.toMap)
        val seqEnv = resultEnv.update(Map(valueName -> eltType))
        val seqs = Array.tabulate(accs.length)(_ => ir_value_expr(seqEnv)(it))
        val res = ir_value_expr(resultEnv)(it)
        ArrayFold2(a, accs, valueName, seqs, res)
      case "ArrayScan" =>
        val accumName = identifier(it)
        val valueName = identifier(it)
        val a = ir_value_expr(env)(it)
        val zero = ir_value_expr(env)(it)
        val eltType = -coerce[TStreamable](a.typ).elementType
        val body = ir_value_expr(env.update(Map(accumName -> zero.typ, valueName -> eltType)))(it)
        ArrayScan(a, zero, accumName, valueName, body)
      case "ArrayLeftJoinDistinct" =>
        val l = identifier(it)
        val r = identifier(it)
        val left = ir_value_expr(env)(it)
        val right = ir_value_expr(env)(it)
        val lelt = coerce[TStreamable](left.typ).elementType
        val relt = coerce[TStreamable](right.typ).elementType
        val comp = ir_value_expr(env.update(Map(l -> lelt, r -> relt)))(it)
        val join = ir_value_expr(env.update(Map(l -> lelt, r -> relt)))(it)
        ArrayLeftJoinDistinct(left, right, l, r, comp, join)
      case "ArrayFor" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> coerce[TStreamable](a.typ).elementType))(it)
        ArrayFor(a, name, body)
      case "ArrayAgg" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val query = ir_value_expr(env + (name -> coerce[TStreamable](a.typ).elementType))(it)
        ArrayAgg(a, name, query)
      case "ArrayAggScan" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val query = ir_value_expr(env + (name -> coerce[TStreamable](a.typ).elementType))(it)
        ArrayAggScan(a, name, query)
      case "AggFilter" =>
        val isScan = boolean_literal(it)
        val cond = ir_value_expr(env)(it)
        val aggIR = ir_value_expr(env)(it)
        AggFilter(cond, aggIR, isScan)
      case "AggExplode" =>
        val name = identifier(it)
        val isScan = boolean_literal(it)
        val a = ir_value_expr(env)(it)
        val aggBody = ir_value_expr(env + (name -> coerce[TStreamable](a.typ).elementType))(it)
        AggExplode(a, name, aggBody, isScan)
      case "AggGroupBy" =>
        val isScan = boolean_literal(it)
        val key = ir_value_expr(env)(it)
        val aggIR = ir_value_expr(env)(it)
        AggGroupBy(key, aggIR, isScan)
      case "AggArrayPerElement" =>
        val elementName = identifier(it)
        val indexName = identifier(it)
        val isScan = boolean_literal(it)
        val hasKnownLength = boolean_literal(it)
        val a = ir_value_expr(env)(it)
        val aggBody = ir_value_expr(env
          + (elementName -> coerce[TStreamable](a.typ).elementType)
          + (indexName -> TInt32()))(it)
        val knownLength = if (hasKnownLength) Some(ir_value_expr(env)(it)) else None
        AggArrayPerElement(a, elementName, indexName, aggBody, knownLength, isScan)
      case "ApplyAggOp" =>
        val aggOp = agg_op(it)
        val ctorArgs = ir_value_exprs(env)(it)
        val initOpArgs = opt(it, ir_value_exprs(env))
        val seqOpArgs = ir_value_exprs(env)(it)
        val aggSig = AggSignature(aggOp, ctorArgs.map(arg => -arg.typ), initOpArgs.map(_.map(arg => -arg.typ)), seqOpArgs.map(arg => -arg.typ))
        ApplyAggOp(ctorArgs, initOpArgs.map(_.toFastIndexedSeq), seqOpArgs, aggSig)
      case "ApplyScanOp" =>
        val aggOp = agg_op(it)
        val ctorArgs = ir_value_exprs(env)(it)
        val initOpArgs = opt(it, ir_value_exprs(env))
        val seqOpArgs = ir_value_exprs(env)(it)
        val aggSig = AggSignature(aggOp, ctorArgs.map(arg => -arg.typ), initOpArgs.map(_.map(arg => -arg.typ)), seqOpArgs.map(arg => -arg.typ))
        ApplyScanOp(ctorArgs, initOpArgs.map(_.toFastIndexedSeq), seqOpArgs, aggSig)
      case "InitOp2" =>
        val i = int32_literal(it)
        val aggSig = agg_signature2(env.typEnv)(it)
        val args = ir_value_exprs(env)(it)
        InitOp2(i, args, aggSig)
      case "SeqOp2" =>
        val i = int32_literal(it)
        val aggSig = agg_signature2(env.typEnv)(it)
        val args = ir_value_exprs(env)(it)
        SeqOp2(i, args, aggSig)
      case "CombOp2" =>
        val i1 = int32_literal(it)
        val i2 = int32_literal(it)
        val aggSig = agg_signature2(env.typEnv)(it)
        CombOp2(i1, i2, aggSig)
      case "ResultOp2" =>
        val i = int32_literal(it)
        val aggSigs = agg_signatures(env.typEnv)(it)
        ResultOp2(i, aggSigs)
      case "SerializeAggs" =>
        val i = int32_literal(it)
        val i2 = int32_literal(it)
        val spec = BufferSpec.parse(string_literal(it))
        val aggSigs = agg_signatures(env.typEnv)(it)
        SerializeAggs(i, i2, spec, aggSigs)
      case "DeserializeAggs" =>
        val i = int32_literal(it)
        val i2 = int32_literal(it)
        val spec = BufferSpec.parse(string_literal(it))
        val aggSigs = agg_signatures(env.typEnv)(it)
        DeserializeAggs(i, i2, spec, aggSigs)
      case "Begin" =>
        val xs = ir_value_children(env)(it)
        Begin(xs)
      case "MakeStruct" =>
        val fields = named_value_irs(env)(it)
        MakeStruct(fields)
      case "SelectFields" =>
        val fields = identifiers(it)
        val old = ir_value_expr(env)(it)
        SelectFields(old, fields)
      case "InsertFields" =>
        val old = ir_value_expr(env)(it)
        val fieldOrder = opt(it, string_literals)
        val fields = named_value_irs(env)(it)
        InsertFields(old, fields, fieldOrder.map(_.toFastIndexedSeq))
      case "GetField" =>
        val name = identifier(it)
        val s = ir_value_expr(env)(it)
        GetField(s, name)
      case "MakeTuple" =>
        val indices = int32_literals(it)
        val args = ir_value_children(env)(it)
        MakeTuple(indices.zip(args))
      case "GetTupleElement" =>
        val idx = int32_literal(it)
        val tuple = ir_value_expr(env)(it)
        GetTupleElement(tuple, idx)
      case "In" =>
        val typ = type_expr(env.typEnv)(it)
        val idx = int32_literal(it)
        In(idx, typ.physicalType)
      case "Die" =>
        val typ = type_expr(env.typEnv)(it)
        val msg = ir_value_expr(env)(it)
        Die(msg, typ)
      case "ApplySeeded" =>
        val function = identifier(it)
        val seed = int64_literal(it)
        val rt = type_expr(env.typEnv)(it)
        val args = ir_value_children(env)(it)
        ApplySeeded(function, args, seed, rt)
      case "ApplyIR" | "ApplySpecial" | "Apply" =>
        val function = identifier(it)
        val rt = type_expr(env.typEnv)(it)
        val args = ir_value_children(env)(it)
        invoke(function, rt, args: _*)
      case "Uniroot" =>
        val name = identifier(it)
        val function = ir_value_expr(env + (name -> TFloat64()))(it)
        val min = ir_value_expr(env)(it)
        val max = ir_value_expr(env)(it)
        Uniroot(name, function, min, max)
      case "TableCount" =>
        val child = table_ir(env.withRefMap(Map.empty))(it)
        TableCount(child)
      case "TableGetGlobals" =>
        val child = table_ir(env.withRefMap(Map.empty))(it)
        TableGetGlobals(child)
      case "TableCollect" =>
        val child = table_ir(env.withRefMap(Map.empty))(it)
        TableCollect(child)
      case "TableAggregate" =>
        val child = table_ir(env.withRefMap(Map.empty))(it)
        val query = ir_value_expr(env.update(child.typ.refMap))(it)
        TableAggregate(child, query)
      case "TableToValueApply" =>
        val config = string_literal(it)
        val child = table_ir(env)(it)
        TableToValueApply(child, RelationalFunctions.lookupTableToValue(config))
      case "MatrixToValueApply" =>
        val config = string_literal(it)
        val child = matrix_ir(env)(it)
        MatrixToValueApply(child, RelationalFunctions.lookupMatrixToValue(config))
      case "BlockMatrixToValueApply" =>
        val config = string_literal(it)
        val child = blockmatrix_ir(env)(it)
        BlockMatrixToValueApply(child, RelationalFunctions.lookupBlockMatrixToValue(config))
      case "TableWrite" =>
        implicit val formats = TableWriter.formats
        val writerStr = string_literal(it)
        val child = table_ir(env)(it)
        TableWrite(child, deserialize[TableWriter](writerStr))
      case "TableMultiWrite" =>
        implicit val formats = WrappedMatrixNativeMultiWriter.formats
        val writerStr = string_literal(it)
        val children = table_ir_children(env)(it)
        TableMultiWrite(children, deserialize[WrappedMatrixNativeMultiWriter](writerStr))
      case "MatrixAggregate" =>
        val child = matrix_ir(env.withRefMap(Map.empty))(it)
        val query = ir_value_expr(env.update(child.typ.refMap))(it)
        MatrixAggregate(child, query)
      case "MatrixWrite" =>
        val writerStr = string_literal(it)
        implicit val formats: Formats = MatrixWriter.formats
        val writer = deserialize[MatrixWriter](writerStr)
        val child = matrix_ir(env.withRefMap(Map.empty))(it)
        MatrixWrite(child, writer)
      case "MatrixMultiWrite" =>
        val writerStr = string_literal(it)
        implicit val formats = MatrixNativeMultiWriter.formats
        val writer = deserialize[MatrixNativeMultiWriter](writerStr)
        val children = matrix_ir_children(env)(it)
        MatrixMultiWrite(children, writer)
      case "BlockMatrixWrite" =>
        val writerStr = string_literal(it)
        implicit val formats: Formats = BlockMatrixWriter.formats
        val writer = deserialize[BlockMatrixWriter](writerStr)
        val child = blockmatrix_ir(env)(it)
        BlockMatrixWrite(child, writer)
      case "BlockMatrixMultiWrite" =>
        val writerStr = string_literal(it)
        implicit val formats: Formats = BlockMatrixWriter.formats
        val writer = deserialize[BlockMatrixMultiWriter](writerStr)
        val blockMatrices = repUntil(it, blockmatrix_ir(env), PunctuationToken(")"))
        BlockMatrixMultiWrite(blockMatrices.toFastIndexedSeq, writer)
      case "CollectDistributedArray" =>
        val cname = identifier(it)
        val gname = identifier(it)
        val ctxs = ir_value_expr(env)(it)
        val globals = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (cname -> coerce[TStreamable](ctxs.typ).elementType) + (gname -> globals.typ))(it)
        CollectDistributedArray(ctxs, globals, cname, gname, body)
      case "JavaIR" =>
        val name = identifier(it)
        env.irMap(name).asInstanceOf[IR]
      case "ReadPartition" =>
        import AbstractRVDSpec.formats
        val spec = JsonMethods.parse(string_literal(it)).extract[AbstractTypedCodecSpec]
        val rowType = coerce[TStruct](type_expr(env.typEnv)(it))
        val path = ir_value_expr(env)(it)
        ReadPartition(path, spec, rowType)
    }
  }

  def table_irs(env: IRParserEnvironment)(it: TokenIterator): Array[TableIR] = {
    punctuation(it, "(")
    val tirs = table_ir_children(env)(it)
    punctuation(it, ")")
    tirs
  }

  def table_ir_children(env: IRParserEnvironment)(it: TokenIterator): Array[TableIR] =
    repUntil(it, table_ir(env), PunctuationToken(")"))

  def table_ir(env: IRParserEnvironment)(it: TokenIterator): TableIR = {
    punctuation(it, "(")
    val ir = table_ir_1(env)(it)
    punctuation(it, ")")
    ir
  }

  def table_ir_1(env: IRParserEnvironment)(it: TokenIterator): TableIR = {
    // FIXME TableImport
    identifier(it) match {
      case "TableKeyBy" =>
        val keys = identifiers(it)
        val isSorted = boolean_literal(it)
        val child = table_ir(env)(it)
        TableKeyBy(child, keys, isSorted)
      case "TableDistinct" =>
        val child = table_ir(env)(it)
        TableDistinct(child)
      case "TableFilter" =>
        val child = table_ir(env)(it)
        val pred = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        TableFilter(child, pred)
      case "TableRead" =>
        val requestedType = opt(it, table_type_expr(env.typEnv))
        val dropRows = boolean_literal(it)
        val readerStr = string_literal(it)
        implicit val formats: Formats = TableReader.formats
        val reader = deserialize[TableReader](readerStr)
        TableRead(requestedType.getOrElse(reader.fullType), dropRows, reader)
      case "MatrixColsTable" =>
        val child = matrix_ir(env)(it)
        MatrixColsTable(child)
      case "MatrixRowsTable" =>
        val child = matrix_ir(env)(it)
        MatrixRowsTable(child)
      case "MatrixEntriesTable" =>
        val child = matrix_ir(env)(it)
        MatrixEntriesTable(child)
      case "TableAggregateByKey" =>
        val child = table_ir(env)(it)
        val expr = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        TableAggregateByKey(child, expr)
      case "TableKeyByAndAggregate" =>
        val nPartitions = opt(it, int32_literal)
        val bufferSize = int32_literal(it)
        val child = table_ir(env)(it)
        val newEnv = env.withRefMap(child.typ.refMap)
        val expr = ir_value_expr(newEnv)(it)
        val newKey = ir_value_expr(newEnv)(it)
        TableKeyByAndAggregate(child, expr, newKey, nPartitions, bufferSize)
      case "TableRepartition" =>
        val n = int32_literal(it)
        val strategy = int32_literal(it)
        val child = table_ir(env)(it)
        TableRepartition(child, n, strategy)
      case "TableHead" =>
        val n = int64_literal(it)
        val child = table_ir(env)(it)
        TableHead(child, n)
      case "TableTail" =>
        val n = int64_literal(it)
        val child = table_ir(env)(it)
        TableTail(child, n)
      case "TableJoin" =>
        val joinType = identifier(it)
        val joinKey = int32_literal(it)
        val left = table_ir(env)(it)
        val right = table_ir(env)(it)
        TableJoin(left, right, joinType, joinKey)
      case "TableLeftJoinRightDistinct" =>
        val root = identifier(it)
        val left = table_ir(env)(it)
        val right = table_ir(env)(it)
        TableLeftJoinRightDistinct(left, right, root)
      case "TableIntervalJoin" =>
        val root = identifier(it)
        val product = boolean_literal(it)
        val left = table_ir(env)(it)
        val right = table_ir(env)(it)
        TableIntervalJoin(left, right, root, product)
      case "TableZipUnchecked" =>
        val left = table_ir(env)(it)
        val right = table_ir(env)(it)
        TableZipUnchecked(left, right)
      case "TableMultiWayZipJoin" =>
        val dataName = string_literal(it)
        val globalsName = string_literal(it)
        val children = table_ir_children(env)(it)
        TableMultiWayZipJoin(children, dataName, globalsName)
      case "TableParallelize" =>
        val nPartitions = opt(it, int32_literal)
        val rowsAndGlobal = ir_value_expr(env)(it)
        TableParallelize(rowsAndGlobal, nPartitions)
      case "TableMapRows" =>
        val child = table_ir(env)(it)
        val newRow = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        TableMapRows(child, newRow)
      case "TableMapGlobals" =>
        val child = table_ir(env)(it)
        val newRow = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        TableMapGlobals(child, newRow)
      case "TableRange" =>
        val n = int32_literal(it)
        val nPartitions = opt(it, int32_literal)
        TableRange(n, nPartitions.getOrElse(HailContext.get.sc.defaultParallelism))
      case "TableUnion" =>
        val children = table_ir_children(env)(it)
        TableUnion(children)
      case "TableOrderBy" =>
        val ids = identifiers(it)
        val child = table_ir(env)(it)
        TableOrderBy(child, ids.map(i =>
          if (i.charAt(0) == 'A')
            SortField(i.substring(1), Ascending)
          else
            SortField(i.substring(1), Descending)))
      case "TableExplode" =>
        val path = string_literals(it)
        val child = table_ir(env)(it)
        TableExplode(child, path)
      case "CastMatrixToTable" =>
        val entriesField = string_literal(it)
        val colsField = string_literal(it)
        val child = matrix_ir(env)(it)
        CastMatrixToTable(child, entriesField, colsField)
      case "MatrixToTableApply" =>
        val config = string_literal(it)
        val child = matrix_ir(env)(it)
        MatrixToTableApply(child, RelationalFunctions.lookupMatrixToTable(config))
      case "TableToTableApply" =>
        val config = string_literal(it)
        val child = table_ir(env)(it)
        TableToTableApply(child, RelationalFunctions.lookupTableToTable(config))
      case "BlockMatrixToTableApply" =>
        val config = string_literal(it)
        val bm = blockmatrix_ir(env)(it)
        val aux = ir_value_expr(env)(it)
        BlockMatrixToTableApply(bm, aux, RelationalFunctions.lookupBlockMatrixToTable(config))
      case "BlockMatrixToTable" =>
        val child = blockmatrix_ir(env)(it)
        BlockMatrixToTable(child)
      case "TableRename" =>
        val rowK = string_literals(it)
        val rowV = string_literals(it)
        val globalK = string_literals(it)
        val globalV = string_literals(it)
        val child = table_ir(env)(it)
        TableRename(child, rowK.zip(rowV).toMap, globalK.zip(globalV).toMap)
      case "TableFilterIntervals" =>
        val intervals = string_literal(it)
        val keep = boolean_literal(it)
        val child = table_ir(env)(it)
        TableFilterIntervals(child,
          JSONAnnotationImpex.importAnnotation(JsonMethods.parse(intervals),
            TArray(TInterval(child.typ.keyType)),
            padNulls = false).asInstanceOf[IndexedSeq[Interval]],
          keep)
      case "RelationalLetTable" =>
        val name = identifier(it)
        val value = ir_value_expr(env)(it)
        val body = table_ir(env)(it)
        RelationalLetTable(name, value, body)
      case "JavaTable" =>
        val name = identifier(it)
        env.irMap(name).asInstanceOf[TableIR]
    }
  }

  def matrix_ir_children(env: IRParserEnvironment)(it: TokenIterator): Array[MatrixIR] =
    repUntil(it, matrix_ir(env), PunctuationToken(")"))

  def matrix_ir(env: IRParserEnvironment)(it: TokenIterator): MatrixIR = {
    punctuation(it, "(")
    val ir = matrix_ir_1(env)(it)
    punctuation(it, ")")
    ir
  }

  def matrix_ir_1(env: IRParserEnvironment)(it: TokenIterator): MatrixIR = {
    identifier(it) match {
      case "MatrixFilterCols" =>
        val child = matrix_ir(env)(it)
        val pred = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixFilterCols(child, pred)
      case "MatrixFilterRows" =>
        val child = matrix_ir(env)(it)
        val pred = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixFilterRows(child, pred)
      case "MatrixFilterEntries" =>
        val child = matrix_ir(env)(it)
        val pred = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixFilterEntries(child, pred)
      case "MatrixMapCols" =>
        val newKey = opt(it, string_literals)
        val child = matrix_ir(env)(it)
        val newCol = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixMapCols(child, newCol, newKey.map(_.toFastIndexedSeq))
      case "MatrixKeyRowsBy" =>
        val key = identifiers(it)
        val isSorted = boolean_literal(it)
        val child = matrix_ir(env)(it)
        MatrixKeyRowsBy(child, key, isSorted)
      case "MatrixMapRows" =>
        val child = matrix_ir(env)(it)
        val newRow = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixMapRows(child, newRow)
      case "MatrixMapEntries" =>
        val child = matrix_ir(env)(it)
        val newEntry = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixMapEntries(child, newEntry)
      case "MatrixUnionCols" =>
        val joinType = identifier(it)
        val left = matrix_ir(env)(it)
        val right = matrix_ir(env)(it)
        MatrixUnionCols(left, right, joinType)
      case "MatrixMapGlobals" =>
        val child = matrix_ir(env)(it)
        val newGlobals = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixMapGlobals(child, newGlobals)
      case "MatrixAggregateColsByKey" =>
        val child = matrix_ir(env)(it)
        val newEnv = env.withRefMap(child.typ.refMap)
        val entryExpr = ir_value_expr(newEnv)(it)
        val colExpr = ir_value_expr(newEnv)(it)
        MatrixAggregateColsByKey(child, entryExpr, colExpr)
      case "MatrixAggregateRowsByKey" =>
        val child = matrix_ir(env)(it)
        val newEnv = env.withRefMap(child.typ.refMap)
        val entryExpr = ir_value_expr(newEnv)(it)
        val rowExpr = ir_value_expr(newEnv)(it)
        MatrixAggregateRowsByKey(child, entryExpr, rowExpr)
      case "MatrixRead" =>
        val requestedType = opt(it, matrix_type_expr(env.typEnv))
        val dropCols = boolean_literal(it)
        val dropRows = boolean_literal(it)
        val readerStr = string_literal(it)
        implicit val formats: Formats = MatrixReader.formats + new MatrixBGENReaderSerializer(env)
        val reader = deserialize[MatrixReader](readerStr)
        MatrixRead(requestedType.getOrElse(reader.fullMatrixType), dropCols, dropRows, reader)
      case "MatrixAnnotateRowsTable" =>
        val root = string_literal(it)
        val product = boolean_literal(it)
        val child = matrix_ir(env)(it)
        val table = table_ir(env)(it)
        MatrixAnnotateRowsTable(child, table, root, product)
      case "MatrixAnnotateColsTable" =>
        val root = string_literal(it)
        val child = matrix_ir(env)(it)
        val table = table_ir(env)(it)
        MatrixAnnotateColsTable(child, table, root)
      case "MatrixExplodeRows" =>
        val path = identifiers(it)
        val child = matrix_ir(env)(it)
        MatrixExplodeRows(child, path)
      case "MatrixExplodeCols" =>
        val path = identifiers(it)
        val child = matrix_ir(env)(it)
        MatrixExplodeCols(child, path)
      case "MatrixChooseCols" =>
        val oldIndices = int32_literals(it)
        val child = matrix_ir(env)(it)
        MatrixChooseCols(child, oldIndices)
      case "MatrixCollectColsByKey" =>
        val child = matrix_ir(env)(it)
        MatrixCollectColsByKey(child)
      case "MatrixRepartition" =>
        val n = int32_literal(it)
        val strategy = int32_literal(it)
        val child = matrix_ir(env)(it)
        MatrixRepartition(child, n, strategy)
      case "MatrixUnionRows" =>
        val children = matrix_ir_children(env)(it)
        MatrixUnionRows(children)
      case "MatrixDistinctByRow" =>
        val child = matrix_ir(env)(it)
        MatrixDistinctByRow(child)
      case "MatrixRowsHead" =>
        val n = int64_literal(it)
        val child = matrix_ir(env)(it)
        MatrixRowsHead(child, n)
      case "MatrixColsHead" =>
        val n = int32_literal(it)
        val child = matrix_ir(env)(it)
        MatrixColsHead(child, n)
      case "MatrixRowsTail" =>
        val n = int64_literal(it)
        val child = matrix_ir(env)(it)
        MatrixRowsTail(child, n)
      case "MatrixColsTail" =>
        val n = int32_literal(it)
        val child = matrix_ir(env)(it)
        MatrixColsTail(child, n)
      case "CastTableToMatrix" =>
        val entriesField = identifier(it)
        val colsField = identifier(it)
        val colKey = identifiers(it)
        val child = table_ir(env)(it)
        CastTableToMatrix(child, entriesField, colsField, colKey)
      case "MatrixToMatrixApply" =>
        val config = string_literal(it)
        val child = matrix_ir(env)(it)
        MatrixToMatrixApply(child, RelationalFunctions.lookupMatrixToMatrix(config))
      case "MatrixRename" =>
        val globalK = string_literals(it)
        val globalV = string_literals(it)
        val colK = string_literals(it)
        val colV = string_literals(it)
        val rowK = string_literals(it)
        val rowV = string_literals(it)
        val entryK = string_literals(it)
        val entryV = string_literals(it)
        val child = matrix_ir(env)(it)
        MatrixRename(child, globalK.zip(globalV).toMap, colK.zip(colV).toMap, rowK.zip(rowV).toMap, entryK.zip(entryV).toMap)
      case "MatrixFilterIntervals" =>
        val intervals = string_literal(it)
        val keep = boolean_literal(it)
        val child = matrix_ir(env)(it)
        MatrixFilterIntervals(child,
          JSONAnnotationImpex.importAnnotation(JsonMethods.parse(intervals),
            TArray(TInterval(child.typ.rowKeyStruct)),
            padNulls = false).asInstanceOf[IndexedSeq[Interval]],
          keep)
      case "RelationalLetMatrixTable" =>
        val name = identifier(it)
        val value = ir_value_expr(env)(it)
        val body = matrix_ir(env)(it)
        RelationalLetMatrixTable(name, value, body)
      case "JavaMatrix" =>
        val name = identifier(it)
        env.irMap(name).asInstanceOf[MatrixIR]
      case "JavaMatrixVectorRef" =>
        val id = int32_literal(it)
        val idx = int32_literal(it)
        HailContext.get.irVectors(id)(idx).asInstanceOf[MatrixIR]
    }
  }

  def blockmatrix_ir(env: IRParserEnvironment)(it: TokenIterator): BlockMatrixIR = {
    punctuation(it, "(")
    val ir = blockmatrix_ir1(env)(it)
    punctuation(it, ")")
    ir
  }

  def blockmatrix_ir1(env: IRParserEnvironment)(it: TokenIterator): BlockMatrixIR = {
    identifier(it) match {
      case "BlockMatrixRead" =>
        val readerStr = string_literal(it)
        implicit val formats: Formats = BlockMatrixReader.formats
        val reader = deserialize[BlockMatrixReader](readerStr)
        BlockMatrixRead(reader)
      case "BlockMatrixMap" =>
        val name = identifier(it)
        val child = blockmatrix_ir(env)(it)
        val f = ir_value_expr(env + (name -> child.typ.elementType))(it)
        BlockMatrixMap(child, name, f)
      case "BlockMatrixMap2" =>
        val lName = identifier(it)
        val rName = identifier(it)
        val left = blockmatrix_ir(env)(it)
        val right = blockmatrix_ir(env)(it)
        val f = ir_value_expr(env.update(Map(lName -> left.typ.elementType, rName -> right.typ.elementType)))(it)
        BlockMatrixMap2(left, right, lName, rName, f)
      case "BlockMatrixDot" =>
        val left = blockmatrix_ir(env)(it)
        val right = blockmatrix_ir(env)(it)
        BlockMatrixDot(left, right)
      case "BlockMatrixBroadcast" =>
        val inIndexExpr = int32_literals(it)
        val shape = int64_literals(it)
        val blockSize = int32_literal(it)
        val child = blockmatrix_ir(env)(it)
        BlockMatrixBroadcast(child, inIndexExpr, shape, blockSize)
      case "BlockMatrixAgg" =>
        val outIndexExpr = int32_literals(it)
        val child = blockmatrix_ir(env)(it)
        BlockMatrixAgg(child, outIndexExpr)
      case "BlockMatrixFilter" =>
        val indices = literals(literals(int64_literal))(it)
        val child = blockmatrix_ir(env)(it)
        BlockMatrixFilter(child, indices)
      case "BlockMatrixSlice" =>
        val slices = literals(literals(int64_literal))(it)
        val child = blockmatrix_ir(env)(it)
        BlockMatrixSlice(child, slices.map(_.toFastIndexedSeq).toFastIndexedSeq)
      case "ValueToBlockMatrix" =>
        val shape = int64_literals(it)
        val blockSize = int32_literal(it)
        val child = ir_value_expr(env)(it)
        ValueToBlockMatrix(child, shape, blockSize)
      case "BlockMatrixRandom" =>
        val seed = int64_literal(it)
        val gaussian = boolean_literal(it)
        val shape = int64_literals(it)
        val blockSize = int32_literal(it)
        BlockMatrixRandom(seed, gaussian, shape, blockSize)
      case "RelationalLetBlockMatrix" =>
        val name = identifier(it)
        val value = ir_value_expr(env)(it)
        val body = blockmatrix_ir(env)(it)
        RelationalLetBlockMatrix(name, value, body)
      case "JavaBlockMatrix" =>
        val name = identifier(it)
        env.irMap(name).asInstanceOf[BlockMatrixIR]
    }
  }

  def parse[T](s: String, f: (TokenIterator) => T): T = {
    val it = IRLexer.parse(s).toIterator.buffered
    f(it)
  }

  def parse_value_ir(s: String): IR = parse_value_ir(s, IRParserEnvironment())
  def parse_value_ir(s: String, refMap: java.util.Map[String, String], irMap: java.util.Map[String, BaseIR]): IR =
    parse_value_ir(s, IRParserEnvironment(refMap.asScala.toMap.mapValues(parseType), irMap.asScala.toMap))
  def parse_value_ir(s: String, env: IRParserEnvironment): IR = parse(s, ir_value_expr(env))

  def parse_table_ir(s: String): TableIR = parse_table_ir(s, IRParserEnvironment())
  def parse_table_ir(s: String, refMap: java.util.Map[String, String], irMap: java.util.Map[String, BaseIR]): TableIR =
    parse_table_ir(s, IRParserEnvironment(refMap.asScala.toMap.mapValues(parseType), irMap.asScala.toMap))
  def parse_table_ir(s: String, env: IRParserEnvironment): TableIR = parse(s, table_ir(env))

  def parse_matrix_ir(s: String): MatrixIR = parse_matrix_ir(s, IRParserEnvironment())
  def parse_matrix_ir(s: String, refMap: java.util.Map[String, String], irMap: java.util.Map[String, BaseIR]): MatrixIR =
    parse_matrix_ir(s, IRParserEnvironment(refMap.asScala.toMap.mapValues(parseType), irMap.asScala.toMap))
  def parse_matrix_ir(s: String, env: IRParserEnvironment): MatrixIR = parse(s, matrix_ir(env))

  def parse_blockmatrix_ir(s: String): BlockMatrixIR = parse_blockmatrix_ir(s, IRParserEnvironment())
  def parse_blockmatrix_ir(s: String, refMap: java.util.Map[String, String], irMap: java.util.Map[String, BaseIR])
  : BlockMatrixIR =
    parse_blockmatrix_ir(s, IRParserEnvironment(refMap.asScala.toMap.mapValues(parseType), irMap.asScala.toMap))
  def parse_blockmatrix_ir(s: String, env: IRParserEnvironment): BlockMatrixIR = parse(s, blockmatrix_ir(env))

  def parseType(code: String, env: TypeParserEnvironment): Type = parse(code, type_expr(env))

  def parsePType(code: String, env: TypeParserEnvironment): PType = parse(code, type_expr(env)).physicalType

  def parseStructType(code: String, env: TypeParserEnvironment): TStruct = coerce[TStruct](parse(code, type_expr(env)))

  def parseUnionType(code: String, env: TypeParserEnvironment): TUnion = coerce[TUnion](parse(code, type_expr(env)))

  def parseRVDType(code: String, env: TypeParserEnvironment): RVDType = parse(code, rvd_type_expr(env))

  def parseTableType(code: String, env: TypeParserEnvironment): TableType = parse(code, table_type_expr(env))

  def parseMatrixType(code: String, env: TypeParserEnvironment): MatrixType = parse(code, matrix_type_expr(env))

  def parseType(code: String): Type = parseType(code, TypeParserEnvironment.default)

  def parsePType(code: String): PType = parsePType(code, TypeParserEnvironment.default)

  def parseStructType(code: String): TStruct = parseStructType(code, TypeParserEnvironment.default)

  def parseUnionType(code: String): TUnion = parseUnionType(code, TypeParserEnvironment.default)

  def parseRVDType(code: String): RVDType = parseRVDType(code, TypeParserEnvironment.default)

  def parseTableType(code: String): TableType = parseTableType(code, TypeParserEnvironment.default)

  def parseMatrixType(code: String): MatrixType = parseMatrixType(code, TypeParserEnvironment.default)
}
