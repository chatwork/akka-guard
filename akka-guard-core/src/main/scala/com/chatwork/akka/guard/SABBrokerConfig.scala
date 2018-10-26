package com.chatwork.akka.guard

import scala.concurrent.duration.{ Duration, FiniteDuration }

case class SABBrokerConfig(maxFailures: Long,
                           backoff: Backoff,
                           failureTimeout: FiniteDuration,
                           receiveTimeout: Option[Duration] = None)
