package com.chatwork.akka.guard

import scala.concurrent.duration.FiniteDuration

sealed trait BackoffReset

final case object ManualReset extends BackoffReset

final case class AutoReset(resetBackoff: FiniteDuration) extends BackoffReset
