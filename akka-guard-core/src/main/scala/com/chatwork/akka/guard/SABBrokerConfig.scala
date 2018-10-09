package com.chatwork.akka.guard

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.Try

case class SABBrokerConfig[T, R](
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration,
    failedResponse: Try[R],
    isFailed: R => Boolean,
    receiveTimeout: Option[Duration] = None,
    eventHandler: Option[(ID, ServiceAttackBlockerStatus) => Unit] = None
)
