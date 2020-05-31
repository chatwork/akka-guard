package com.chatwork.akka.guard.typed.config

import scala.concurrent.duration.FiniteDuration

sealed trait BackoffReset
case object ManualReset                                  extends BackoffReset
final case class AutoReset(resetBackoff: FiniteDuration) extends BackoffReset
