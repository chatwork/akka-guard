package com.chatwork.akka.guard.typed.config

import scala.concurrent.duration.{ Duration, FiniteDuration }

case class SABConfig(
    maxFailures: Long,
    failureDuration: FiniteDuration,
    backoff: Backoff,
    guardResetTimeout: Option[Duration] = None
)
