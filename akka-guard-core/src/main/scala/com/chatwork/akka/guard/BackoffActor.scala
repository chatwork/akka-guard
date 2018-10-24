package com.chatwork.akka.guard

import akka.actor.{ Actor, Cancellable, PoisonPill }
import com.chatwork.akka.guard.SABActor._

sealed trait BackoffActor[B <: Backoff] {
  protected val backoff: B
  protected def createResetBackoffSchedule(attempt: Long): Option[Cancellable]
  protected def reset(attempt: Long): Unit
}

trait ExponentialBackoffActor extends BackoffActor[ExponentialBackoff] {
  _: Actor with FailureTimeoutSchedule with BecomeClosedActor =>
  import context.dispatcher

  private def createResetBackoffSchedule(): Option[Cancellable] = {
    backoff.backoffReset match {
      case AutoReset(resetBackoff) =>
        Some(
          context.system.scheduler.scheduleOnce(resetBackoff, self, BecameClosed(0, 0, setTimer = true))
        )
      case _ =>
        None
    }
  }

  override protected def createResetBackoffSchedule(attempt: Long): Option[Cancellable] = {
    val d = backoff.toDuration(attempt)
    if (backoff.maxBackoff <= d)
      createResetBackoffSchedule()
    else
      Some(context.system.scheduler.scheduleOnce(d, self, BecameClosed(attempt, 0, setTimer = true)))
  }

  override protected def reset(attempt: Long): Unit = {
    createFailureTimeoutSchedule()
    becomeClosed(attempt, failureCount = 0, fireEventHandler = false)
  }
}

trait LinealBackoffActor extends BackoffActor[LinealBackoff] {
  _: Actor =>
  import context.dispatcher

  override protected def createResetBackoffSchedule(attempt: Long): Option[Cancellable] =
    Some(context.system.scheduler.scheduleOnce(backoff.toDuration(attempt), self, PoisonPill))

  override protected def reset(attempt: Long): Unit = {
    context.stop(self)
  }
}
