package is.hail.expr.ir

import java.io.PrintWriter

import is.hail.annotations._
import is.hail.annotations.aggregators.RegionValueAggregator
import is.hail.asm4s._
import is.hail.expr.types.physical.PType
import is.hail.expr.types.virtual.Type
import is.hail.utils._

import scala.reflect.{ClassTag, classTag}

case class CodeCacheKey(aggSigs: IndexedSeq[AggSignature2], args: Seq[(String, PType)], nSpecialArgs: Int, body: IR)

case class CodeCacheValue(typ: PType, f: (Int, Region) => Any)

object Compile {
  private[this] val codeCache: Cache[CodeCacheKey, CodeCacheValue] = new Cache(50)

  private def apply[F >: Null : TypeInfo, R: TypeInfo : ClassTag](
    print: Option[PrintWriter],
    args: Seq[(String, PType, ClassTag[_])],
    argTypeInfo: Array[MaybeGenericTypeInfo[_]],
    body: IR,
    nSpecialArgs: Int,
    optimize: Boolean
  ): (PType, (Int, Region) => F) = {
    val normalizeNames = new NormalizeNames(_.toString)
    val normalizedBody = normalizeNames(body,
      Env(args.map { case (n, _, _) => n -> n }: _*))
    val k = CodeCacheKey(FastIndexedSeq[AggSignature2](), args.map { case (n, pt, _) => (n, pt) }, nSpecialArgs, normalizedBody)
    codeCache.get(k) match {
      case Some(v) =>
        return (v.typ, v.f.asInstanceOf[(Int, Region) => F])
      case None =>
    }

    val fb = new EmitFunctionBuilder[F](argTypeInfo, GenericTypeInfo[R]())

    var ir = body
    if (optimize)
      ir = Optimize(ir, noisy = true, canGenerateLiterals = false, context = Some("Compile"))
    TypeCheck(ir, BindingEnv(Env.fromSeq[Type](args.map { case (name, t, _) => name -> t.virtualType })))

    val env = args
      .zipWithIndex
      .foldLeft(Env.empty[IR]) { case (e, ((n, t, _), i)) => e.bind(n, In(i, t.virtualType)) }

    ir = Subst(ir, BindingEnv(env))
    assert(TypeToIRIntermediateClassTag(ir.typ) == classTag[R])

    Emit(ir, fb, nSpecialArgs)

    val f = fb.resultWithIndex(print)
    codeCache += k -> CodeCacheValue(ir.pType, f)
    (ir.pType, f)
  }

  def apply[F >: Null : TypeInfo, R: TypeInfo : ClassTag](
    print: Option[PrintWriter],
    args: Seq[(String, PType, ClassTag[_])],
    body: IR,
    nSpecialArgs: Int,
    optimize: Boolean
  ): (PType, (Int, Region) => F) = {
    assert(args.forall { case (_, t, ct) => TypeToIRIntermediateClassTag(t.virtualType) == ct })

    val ab = new ArrayBuilder[MaybeGenericTypeInfo[_]]()
    ab += GenericTypeInfo[Region]()
    if (nSpecialArgs == 2)
      ab += GenericTypeInfo[Array[RegionValueAggregator]]()
    args.foreach { case (_, t, _) =>
      ab += GenericTypeInfo()(typeToTypeInfo(t))
      ab += GenericTypeInfo[Boolean]()
    }

    val argTypeInfo: Array[MaybeGenericTypeInfo[_]] = ab.result()

    Compile[F, R](print, args, argTypeInfo, body, nSpecialArgs, optimize)
  }

  def apply[R: TypeInfo : ClassTag](
    body: IR,
    print: Option[PrintWriter]
  ): (PType, (Int, Region) => AsmFunction1[Region, R]) = {
    apply[AsmFunction1[Region, R], R](print, FastSeq[(String, PType, ClassTag[_])](), body, 1, optimize = true)
  }

  def apply[T0: ClassTag, R: TypeInfo : ClassTag](
    name0: String,
    typ0: PType,
    body: IR,
    optimize: Boolean,
    print: Option[PrintWriter]
  ): (PType, (Int, Region) => AsmFunction3[Region, T0, Boolean, R]) = {
    apply[AsmFunction3[Region, T0, Boolean, R], R](print, FastSeq((name0, typ0, classTag[T0])), body, 1, optimize)
  }

  def apply[T0: ClassTag, R: TypeInfo : ClassTag](
    name0: String,
    typ0: PType,
    body: IR,
    optimize: Boolean): (PType, (Int, Region) => AsmFunction3[Region, T0, Boolean, R]) =
    apply(name0, typ0, body, optimize, None)

  def apply[T0: ClassTag, R: TypeInfo : ClassTag](
    name0: String,
    typ0: PType,
    body: IR): (PType, (Int, Region) => AsmFunction3[Region, T0, Boolean, R]) =
    apply(name0, typ0, body, true)

  def apply[T0: ClassTag, T1: ClassTag, R: TypeInfo : ClassTag](
    name0: String,
    typ0: PType,
    name1: String,
    typ1: PType,
    body: IR,
    print: Option[PrintWriter]): (PType, (Int, Region) => AsmFunction5[Region, T0, Boolean, T1, Boolean, R]) = {
    apply[AsmFunction5[Region, T0, Boolean, T1, Boolean, R], R](print, FastSeq((name0, typ0, classTag[T0]), (name1, typ1, classTag[T1])), body, 1, optimize = true)
  }

  def apply[T0: ClassTag, T1: ClassTag, R: TypeInfo : ClassTag](
    name0: String,
    typ0: PType,
    name1: String,
    typ1: PType,
    body: IR): (PType, (Int, Region) => AsmFunction5[Region, T0, Boolean, T1, Boolean, R]) =
    apply(name0, typ0, name1, typ1, body, None)

  def apply[
  T0: TypeInfo : ClassTag,
  T1: TypeInfo : ClassTag,
  T2: TypeInfo : ClassTag,
  R: TypeInfo : ClassTag
  ](name0: String,
    typ0: PType,
    name1: String,
    typ1: PType,
    name2: String,
    typ2: PType,
    body: IR
  ): (PType, (Int, Region) => AsmFunction7[Region, T0, Boolean, T1, Boolean, T2, Boolean, R]) = {
    apply[AsmFunction7[Region, T0, Boolean, T1, Boolean, T2, Boolean, R], R](None, FastSeq(
      (name0, typ0, classTag[T0]),
      (name1, typ1, classTag[T1]),
      (name2, typ2, classTag[T2])
    ), body, 1,
      optimize = true)
  }

  def apply[
  T0: TypeInfo : ClassTag,
  T1: TypeInfo : ClassTag,
  T2: TypeInfo : ClassTag,
  T3: TypeInfo : ClassTag,
  R: TypeInfo : ClassTag
  ](name0: String, typ0: PType,
    name1: String, typ1: PType,
    name2: String, typ2: PType,
    name3: String, typ3: PType,
    body: IR
  ): (PType, (Int, Region) => AsmFunction9[Region, T0, Boolean, T1, Boolean, T2, Boolean, T3, Boolean, R]) = {
    apply[AsmFunction9[Region, T0, Boolean, T1, Boolean, T2, Boolean, T3, Boolean, R], R](None, FastSeq(
      (name0, typ0, classTag[T0]),
      (name1, typ1, classTag[T1]),
      (name2, typ2, classTag[T2]),
      (name3, typ3, classTag[T3])
    ), body, 1,
      optimize = true)
  }

  def apply[
  T0: ClassTag,
  T1: ClassTag,
  T2: ClassTag,
  T3: ClassTag,
  T4: ClassTag,
  T5: ClassTag,
  R: TypeInfo : ClassTag
  ](name0: String, typ0: PType,
    name1: String, typ1: PType,
    name2: String, typ2: PType,
    name3: String, typ3: PType,
    name4: String, typ4: PType,
    name5: String, typ5: PType,
    body: IR
  ): (PType, (Int, Region) => AsmFunction13[Region, T0, Boolean, T1, Boolean, T2, Boolean, T3, Boolean, T4, Boolean, T5, Boolean, R]) = {

    apply[AsmFunction13[Region, T0, Boolean, T1, Boolean, T2, Boolean, T3, Boolean, T4, Boolean, T5, Boolean, R], R](None, FastSeq(
      (name0, typ0, classTag[T0]),
      (name1, typ1, classTag[T1]),
      (name2, typ2, classTag[T2]),
      (name3, typ3, classTag[T3]),
      (name4, typ4, classTag[T4]),
      (name5, typ5, classTag[T5])
    ), body, 1,
      optimize = true)
  }
}

object CompileWithAggregators2 {
  private[this] val codeCache: Cache[CodeCacheKey, CodeCacheValue] = new Cache(50)

  private def apply[F >: Null : TypeInfo, R: TypeInfo : ClassTag](
    aggSigs: Array[AggSignature2],
    args: Seq[(String, PType, ClassTag[_])],
    argTypeInfo: Array[MaybeGenericTypeInfo[_]],
    body: IR,
    nSpecialArgs: Int,
    optimize: Boolean
  ): (PType, (Int, Region) => (F with FunctionWithAggRegion)) = {
    val normalizeNames = new NormalizeNames(_.toString)
    val normalizedBody = normalizeNames(body,
      Env(args.map { case (n, _, _) => n -> n }: _*))
    val k = CodeCacheKey(aggSigs.toFastIndexedSeq, args.map { case (n, pt, _) => (n, pt) }, nSpecialArgs, normalizedBody)
    codeCache.get(k) match {
      case Some(v) =>
        return (v.typ, v.f.asInstanceOf[(Int, Region) => (F with FunctionWithAggRegion)])
      case None =>
    }

    val fb = new EmitFunctionBuilder[F](argTypeInfo, GenericTypeInfo[R]())

    var ir = body
    if (optimize)
      ir = Optimize(ir, noisy = true, canGenerateLiterals = false, context = Some("Compile"))
    TypeCheck(ir, BindingEnv(Env.fromSeq[Type](args.map { case (name, t, _) => name -> t.virtualType })))

    val env = args
      .zipWithIndex
      .foldLeft(Env.empty[IR]) { case (e, ((n, t, _), i)) => e.bind(n, In(i, t.virtualType)) }

    ir = Subst(ir, BindingEnv(env))
    assert(TypeToIRIntermediateClassTag(ir.typ) == classTag[R])

    Emit(ir, fb, nSpecialArgs, Some(aggSigs))

    val f = fb.resultWithIndex()
    codeCache += k -> CodeCacheValue(ir.pType, f)
    (ir.pType, f.asInstanceOf[(Int, Region) => (F with FunctionWithAggRegion)])
  }

  def apply[F >: Null : TypeInfo, R: TypeInfo : ClassTag](
    aggSigs: Array[AggSignature2],
    args: Seq[(String, PType, ClassTag[_])],
    body: IR,
    nSpecialArgs: Int,
    optimize: Boolean
  ): (PType, (Int, Region) => (F with FunctionWithAggRegion)) = {
    assert(args.forall { case (_, t, ct) => TypeToIRIntermediateClassTag(t.virtualType) == ct })

    val ab = new ArrayBuilder[MaybeGenericTypeInfo[_]]()
    ab += GenericTypeInfo[Region]()
    if (nSpecialArgs == 2)
      ab += GenericTypeInfo[Array[RegionValueAggregator]]()
    args.foreach { case (_, t, _) =>
      ab += GenericTypeInfo()(typeToTypeInfo(t))
      ab += GenericTypeInfo[Boolean]()
    }

    val argTypeInfo: Array[MaybeGenericTypeInfo[_]] = ab.result()

    CompileWithAggregators2[F, R](aggSigs, args, argTypeInfo, body, nSpecialArgs, optimize)
  }

  def apply[R: TypeInfo : ClassTag](
    aggSigs: Array[AggSignature2],
    body: IR): (PType, (Int, Region) => AsmFunction1[Region, R] with FunctionWithAggRegion) = {

    apply[AsmFunction1[Region, R], R](aggSigs, FastSeq[(String, PType, ClassTag[_])](), body, 1, optimize = true)
  }

  def apply[T0: ClassTag, R: TypeInfo : ClassTag](
    aggSigs: Array[AggSignature2],
    name0: String, typ0: PType,
    body: IR): (PType, (Int, Region) => AsmFunction3[Region, T0, Boolean, R] with FunctionWithAggRegion) = {

    apply[AsmFunction3[Region, T0, Boolean, R], R](aggSigs, FastSeq((name0, typ0, classTag[T0])), body, 1, optimize = true)
  }

  def apply[T0: ClassTag, T1: ClassTag, R: TypeInfo : ClassTag](
    aggSigs: Array[AggSignature2],
    name0: String, typ0: PType,
    name1: String, typ1: PType,
    body: IR): (PType, (Int, Region) => (AsmFunction5[Region, T0, Boolean, T1, Boolean, R] with FunctionWithAggRegion)) = {

    apply[AsmFunction5[Region, T0, Boolean, T1, Boolean, R], R](aggSigs, FastSeq((name0, typ0, classTag[T0]), (name1, typ1, classTag[T1])), body, 1, optimize = true)
  }
}

object CompileWithAggregators {
  type Compiler[F] = (IR) => (PType, (Int, Region) => F)
  type IRAggFun1[T0] =
    AsmFunction4[Region, Array[RegionValueAggregator], T0, Boolean, Unit]
  type IRAggFun2[T0, T1] =
    AsmFunction6[Region, Array[RegionValueAggregator],
      T0, Boolean,
      T1, Boolean,
      Unit]
  type IRAggFun3[T0, T1, T2] =
    AsmFunction8[Region, Array[RegionValueAggregator],
      T0, Boolean,
      T1, Boolean,
      T2, Boolean,
      Unit]
  type IRAggFun4[T0, T1, T2, T3] =
    AsmFunction10[Region, Array[RegionValueAggregator],
      T0, Boolean,
      T1, Boolean,
      T2, Boolean,
      T3, Boolean,
      Unit]
  type IRFun1[T0, R] =
    AsmFunction3[Region, T0, Boolean, R]
  type IRFun2[T0, T1, R] =
    AsmFunction5[Region, T0, Boolean, T1, Boolean, R]
  type IRFun3[T0, T1, T2, R] =
    AsmFunction7[Region, T0, Boolean, T1, Boolean, T2, Boolean, R]
  type IRFun4[T0, T1, T2, T3, R] =
    AsmFunction9[Region, T0, Boolean, T1, Boolean, T2, Boolean, T3, Boolean, R]

  def liftScan(ir: IR): IR = ir match {
    case ApplyScanOp(a, b, c, d) =>
      ApplyAggOp(a, b, c, d)
    case x => MapIR(liftScan)(x)
  }

  def compileAggIRs[
  FAggInit >: Null : TypeInfo,
  FAggSeq >: Null : TypeInfo
  ](initScopeArgs: Seq[(String, PType, ClassTag[_])],
    aggScopeArgs: Seq[(String, PType, ClassTag[_])],
    body: IR, aggResultName: String
  ): (Array[RegionValueAggregator], (IR, Compiler[FAggInit]), (IR, Compiler[FAggSeq]), PType, IR) = {
    assert((initScopeArgs ++ aggScopeArgs).forall { case (_, t, ct) => TypeToIRIntermediateClassTag(t.virtualType) == ct })

    val ExtractedAggregators(postAggIR, aggResultType, initOpIR, seqOpIR, rvAggs) = ExtractAggregators(body, aggResultName)
    val compileInitOp = (initOp: IR) => Compile[FAggInit, Unit](None, initScopeArgs, initOp, 2, optimize = true)
    val compileSeqOp = (seqOp: IR) => Compile[FAggSeq, Unit](None, aggScopeArgs, seqOp, 2, optimize = true)

    (rvAggs,
      (initOpIR, compileInitOp),
      (seqOpIR, compileSeqOp),
      aggResultType,
      postAggIR)
  }

  def apply[
  F0 >: Null : TypeInfo,
  F1 >: Null : TypeInfo
  ](initScopeArgs: Seq[(String, PType, ClassTag[_])],
    aggScopeArgs: Seq[(String, PType, ClassTag[_])],
    body: IR, aggResultName: String,
    transformInitOp: (Int, IR) => IR,
    transformSeqOp: (Int, IR) => IR
  ): (Array[RegionValueAggregator], (Int, Region) => F0, (Int, Region) => F1, PType, IR) = {
    val (rvAggs, (initOpIR, compileInitOp),
      (seqOpIR, compileSeqOp),
      aggResultType, postAggIR
    ) = compileAggIRs[F0, F1](initScopeArgs, aggScopeArgs, body, aggResultName)

    val nAggs = rvAggs.length
    val (_, initOps) = compileInitOp(trace("initop", transformInitOp(nAggs, initOpIR)))
    val (_, seqOps) = compileSeqOp(trace("seqop", transformSeqOp(nAggs, seqOpIR)))
    (rvAggs, initOps, seqOps, aggResultType, postAggIR)
  }

  private[this] def trace(name: String, t: IR): IR = {
    log.info(name + " " + Pretty(t))
    t
  }

  def apply[
  T0: ClassTag,
  S0: ClassTag,
  S1: ClassTag
  ](name0: String, typ0: PType,
    aggName0: String, aggTyp0: PType,
    aggName1: String, aggTyp1: PType,
    body: IR, aggResultName: String,
    transformInitOp: (Int, IR) => IR,
    transformSeqOp: (Int, IR) => IR
  ): (Array[RegionValueAggregator],
    (Int, Region) => IRAggFun1[T0],
    (Int, Region) => IRAggFun2[S0, S1],
    PType,
    IR) = {
    val args = FastSeq((name0, typ0, classTag[T0]))

    val aggScopeArgs = FastSeq(
      (aggName0, aggTyp0, classTag[S0]),
      (aggName1, aggTyp1, classTag[S1]))

    apply[IRAggFun1[T0], IRAggFun2[S0, S1]](args, aggScopeArgs, body, aggResultName, transformInitOp, transformSeqOp)
  }

  def apply[
  T0: ClassTag,
  T1: ClassTag,
  S0: ClassTag,
  S1: ClassTag,
  S2: ClassTag
  ](name0: String, typ0: PType,
    name1: String, typ1: PType,
    aggName0: String, aggType0: PType,
    aggName1: String, aggType1: PType,
    aggName2: String, aggType2: PType,
    body: IR, aggResultName: String,
    transformInitOp: (Int, IR) => IR,
    transformSeqOp: (Int, IR) => IR
  ): (Array[RegionValueAggregator],
    (Int, Region) => IRAggFun2[T0, T1],
    (Int, Region) => IRAggFun3[S0, S1, S2],
    PType,
    IR) = {
    val args = FastSeq(
      (name0, typ0, classTag[T0]),
      (name1, typ1, classTag[T1]))

    val aggArgs = FastSeq(
      (aggName0, aggType0, classTag[S0]),
      (aggName1, aggType1, classTag[S1]),
      (aggName2, aggType2, classTag[S2]))

    apply[IRAggFun2[T0, T1], IRAggFun3[S0, S1, S2]](args, aggArgs, body, aggResultName, transformInitOp, transformSeqOp)
  }

  def apply[
    T0: ClassTag,
    S0: ClassTag,
    S1: ClassTag,
    S2: ClassTag
  ](name0: String, typ0: PType,
    aggName0: String, aggTyp0: PType,
    aggName1: String, aggTyp1: PType,
    aggName2: String, aggTyp2: PType,
    body: IR, aggResultName: String,
    transformInitOp: (Int, IR) => IR,
    transformSeqOp: (Int, IR) => IR
  ): (Array[RegionValueAggregator],
    (Int, Region) => IRAggFun1[T0],
    (Int, Region) => IRAggFun3[S0, S1, S2],
    PType,
    IR) = {
    val args = FastSeq((name0, typ0, classTag[T0]))

    val aggScopeArgs = FastSeq(
      (aggName0, aggTyp0, classTag[S0]),
      (aggName1, aggTyp1, classTag[S1]),
      (aggName2, aggTyp2, classTag[S1]))

    apply[IRAggFun1[T0], IRAggFun3[S0, S1, S2]](args, aggScopeArgs, body, aggResultName, transformInitOp, transformSeqOp)
  }

  def apply[
  T0: ClassTag,
  T1: ClassTag,
  S0: ClassTag,
  S1: ClassTag,
  S2: ClassTag,
  S3: ClassTag
  ](name0: String, typ0: PType,
    name1: String, typ1: PType,
    aggName0: String, aggType0: PType,
    aggName1: String, aggType1: PType,
    aggName2: String, aggType2: PType,
    aggName3: String, aggType3: PType,
    body: IR, aggResultName: String,
    transformInitOp: (Int, IR) => IR,
    transformSeqOp: (Int, IR) => IR
  ): (Array[RegionValueAggregator],
    (Int, Region) => IRAggFun2[T0, T1],
    (Int, Region) => IRAggFun4[S0, S1, S2, S3],
    PType,
    IR) = {
    val args = FastSeq(
      (name0, typ0, classTag[T0]),
      (name1, typ1, classTag[T1]))

    val aggArgs = FastSeq(
      (aggName0, aggType0, classTag[S0]),
      (aggName1, aggType1, classTag[S1]),
      (aggName2, aggType2, classTag[S2]),
      (aggName3, aggType3, classTag[S3]))

    apply[IRAggFun2[T0, T1], IRAggFun4[S0, S1, S2, S3]
      ](args, aggArgs, body, aggResultName, transformInitOp, transformSeqOp)
  }
}
