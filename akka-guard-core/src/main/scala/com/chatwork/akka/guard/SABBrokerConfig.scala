package com.chatwork.akka.guard

import scala.concurrent.duration.{ Duration, FiniteDuration }

case class SABBrokerConfig(
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration,
    receiveTimeout: Option[Duration] = None
)
