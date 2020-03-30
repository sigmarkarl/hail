package is.hail.expr.ir.agg

import is.hail.annotations.StagedRegionValueBuilder
import is.hail.asm4s._
import is.hail.expr.ir.{EmitClassBuilder, EmitCode, EmitFunctionBuilder}
import is.hail.expr.types.physical.PType

abstract class StagedAggregator {
  type State <: AggregatorState

  def resultType: PType

  def createState(cb: EmitClassBuilder[_]): State

  def initOp(state: State, init: Array[EmitCode], dummy: Boolean): Code[Unit]

  def seqOp(state: State, seq: Array[EmitCode], dummy: Boolean): Code[Unit]

  def combOp(state: State, other: State, dummy: Boolean): Code[Unit]

  def result(state: State, srvb: StagedRegionValueBuilder, dummy: Boolean): Code[Unit]

  def initOp(state: AggregatorState, init: Array[EmitCode]): Code[Unit] = initOp(state.asInstanceOf[State], init, dummy = true)
  def seqOp(state: AggregatorState, seq: Array[EmitCode]): Code[Unit] = seqOp(state.asInstanceOf[State], seq, dummy = true)
  def combOp(state: AggregatorState, other: AggregatorState): Code[Unit] = combOp(state.asInstanceOf[State], other.asInstanceOf[State], dummy = true)
  def result(state: AggregatorState, srvb: StagedRegionValueBuilder): Code[Unit] = result(state.asInstanceOf[State], srvb, dummy = true)
}
