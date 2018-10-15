package com.chatwork.akka.guard

import scala.concurrent.duration.FiniteDuration

case class SABBrokerConfig(
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration
)
