package com.chatwork.akka.guard.typed.config

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration.{ Duration, FiniteDuration }

sealed trait Backoff {
  def toDuration(attempt: Long): FiniteDuration
}

case class LinealBackoff(duration: FiniteDuration) extends Backoff {
  override def toDuration(attempt: Long): FiniteDuration = duration
}

case class ExponentialBackoff(
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    private val reset: Option[BackoffReset] = None
) extends Backoff {
  require(minBackoff > Duration.Zero, "minBackoff must be > 0")
  require(maxBackoff >= minBackoff, "maxBackoff must be >= minBackoff")
  require(0.0 <= randomFactor && randomFactor <= 1.0, "randomFactor must be between 0.0 and 1.0")

  val backoffReset: BackoffReset = reset.getOrElse(AutoReset(minBackoff))

  backoffReset match {
    case AutoReset(resetBackoff) =>
      require(minBackoff <= resetBackoff && resetBackoff <= maxBackoff)
    case _ => // ignore
  }

  override def toDuration(attempt: Long): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (attempt >= 30) // Duration overflow protection (> 100 years)
      maxBackoff
    else
      maxBackoff.min(minBackoff * math.pow(2, attempt.toDouble)) * rnd match {
        case f: FiniteDuration => f
        case _                 => maxBackoff
      }

  }
}
