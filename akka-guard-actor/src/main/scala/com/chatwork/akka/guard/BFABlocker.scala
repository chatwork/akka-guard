package com.chatwork.akka.guard

import akka.actor._

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object BFABlocker {

  def props[T, R](id: String, config: BFABrokerConfig[T, R]): Props = Props(
    new BFABlocker[T, R](
      id = id,
      maxFailures = config.maxFailures,
      failureTimeout = config.failureTimeout,
      resetTimeout = config.resetTimeout,
      failedResponse = config.failedResponse,
      receiveTimeout = config.receiveTimeout,
      eventHandler = config.eventHandler
    )
  )

  def name(id: String): String = s"BFABlocker-$id"

  case object Tick
  case object GetStatus
}

class BFABlocker[T, R](
    id: String,
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration,
    failedResponse: => Try[R],
    receiveTimeout: Option[Duration] = None,
    eventHandler: Option[BFABlockerStatus => Unit] = None
) extends Actor
    with ActorLogging {

  import BFABlocker._
  import context.dispatcher

  type GuardMessage = BFAMessage[T, R]

  // receiveTimeout.foreach(context.setReceiveTimeout)

  override def preStart: Unit = {
    createSchedule
  }

  private def createSchedule: Cancellable =
    context.system.scheduler.scheduleOnce(failureTimeout, self, Tick)

  private val open: Receive = {
    case GetStatus       => sender ! BFABlockerStatus.Open // For debugging
    case Tick            =>
    case _: GuardMessage => sender ! Future.fromTry(failedResponse)
  }

  private def closed(failureCount: Long): Receive = {
    case GetStatus => sender ! BFABlockerStatus.Closed // For debugging

    case Tick =>
      if (failureCount > maxFailures) {
        log.debug("become an open")
        context.become(open)
        context.system.scheduler.scheduleOnce(resetTimeout) {
          createSchedule
          log.debug("become a closed to 0")
          context.become(closed(0))
        }
      } else {
        log.debug("actor stop")
        context.stop(self)
      }

    case msg: GuardMessage =>
      val future = try {
        msg.execute
      } catch {
        case NonFatal(cause) =>
          Future.failed(cause)
      }
      future.onComplete {
        case Failure(ex) =>
          log.debug("failure count is [{}]. cause '{}'", failureCount + 1, Option(ex.getMessage))
          context.become(closed(failureCount + 1))
        case Success(_) =>
          context.become(closed(0))
      }
      sender ! future
  }

  override def receive: Receive = closed(0)

}
