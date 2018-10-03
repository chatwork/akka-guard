package com.chatwork.akka.guard

import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.Try

/**
  *
  * @param maxFailures
  * @param failureTimeout
  * @param resetTimeout
  * @param failedResponse
  * @param f
  * @param receiveTimeout
  * @param eventHandler
  * @tparam T
  * @tparam R
  */
case class BFABrokerConfig[T, R](
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration,
    failedResponse: Try[R],
    f: BFAMessage[T] => Future[R],
    receiveTimeout: Option[Duration] = None,
    eventHandler: Option[BFABlockerStatus => Unit] = None
)
