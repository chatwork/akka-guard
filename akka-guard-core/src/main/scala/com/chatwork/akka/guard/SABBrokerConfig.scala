package com.chatwork.akka.guard

import scala.concurrent.duration.FiniteDuration

case class SABBrokerConfig(
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration,
    receiveTimeout: FiniteDuration,
    isOneShot: Boolean = true
) {
  require(resetTimeout <= receiveTimeout, "resetTimeout <= receiveTimeout")
}

object SABBrokerConfig {
  def apply(maxFailures: Long, failureTimeout: FiniteDuration, resetTimeout: FiniteDuration): SABBrokerConfig =
    new SABBrokerConfig(
      maxFailures = maxFailures,
      failureTimeout = failureTimeout,
      resetTimeout = resetTimeout,
      receiveTimeout = resetTimeout
    )

  def apply(maxFailures: Long,
            failureTimeout: FiniteDuration,
            resetTimeout: FiniteDuration,
            isOneShot: Boolean): SABBrokerConfig =
    new SABBrokerConfig(
      maxFailures = maxFailures,
      failureTimeout = failureTimeout,
      resetTimeout = resetTimeout,
      receiveTimeout = resetTimeout,
      isOneShot = isOneShot
    )
}
