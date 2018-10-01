package com.chatwork.akka.guard

import akka.actor._

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

private[guard] object BFABlocker {

  def props[T, R](id: String, config: BFABrokerConfig[T, R]): Props = Props(
    new BFABlocker[T, R](
      id = id,
      maxFailures = config.maxFailures,
      failureTimeout = config.failureTimeout,
      resetTimeout = config.resetTimeout,
      failedResponse = config.failedResponse,
      f = config.f,
      receiveTimeout = config.receiveTimeout,
      eventHandler = config.eventHandler
    )
  )

  def name(id: String): String = s"BFABlocker-$id"

  case object Tick
  case object GetStatus
}

private[guard] class BFABlocker[T, R](
    id: String,
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration,
    failedResponse: => Try[R],
    f: BFAMessage[T] => Future[R],
    receiveTimeout: Option[Duration] = None,
    eventHandler: Option[BFABlockerStatus => Unit] = None
) extends Actor
    with ActorLogging {
  import BFABlocker._
  import context.dispatcher

  // receiveTimeout.foreach(context.setReceiveTimeout)

  override def preStart: Unit = {
    createSchedule
  }

  private def createSchedule: Cancellable =
    context.system.scheduler.scheduleOnce(failureTimeout, self, Tick)

  //  private val open: Receive = {
  //    case GetStatus    => sender() ! BFABlockerStatus.Open
  //    case Tick         =>
  //    case msg: Message => sender ! Future.fromTry(failedResponse)
  //  }
  //
  //  private def closed(failureCount: Long): Receive = {
  //    case GetStatus =>
  //      sender() ! BFABlockerStatus.Closed
  //
  //    case Tick =>
  //      if (failureCount > maxFailures) {
  //        context.become(open)
  //        context.system.scheduler.scheduleOnce(resetTimeout) {
  //          createSchedule
  //          context.unbecome()
  //        }
  //      } else context.become(closed(0))
  //
  //    case msg: Message =>
  //      val future = f(msg.body.asInstanceOf[T])
  //      future.onComplete {
  //        case Failure(ex) =>
  //          context.become(closed(failureCount + 1))
  //        case _ =>
  //          context.become(closed(0))
  //      }
  //      sender ! future
  //  }

  //  private def open: Receive = ???

  private def closed(failureCount: Long): Receive = {
    // For debugging
    case GetStatus =>
      sender ! BFABlockerStatus.Closed

    case Tick =>
      if (failureCount > maxFailures) {
        log.debug("Become an open status.")
        //        context.become(open)
      } else {
        log.debug("Become a close 0.")
        context.become(closed(0))
      }

    case msg: BFAMessage[T] =>
      val future = try {
        f(msg)
      } catch {
        case NonFatal(cause) =>
          Future.failed(cause)
      }
      future.onComplete {
        case Failure(ex) =>
          log.debug("failure count is [{}]. cause message is [{}]", failureCount + 1, Option(ex.getMessage))
          context.become(closed(failureCount + 1))
        case Success(_) =>
          context.become(closed(0))
      }
      //      import akka.pattern.pipe
      //      future.pipeTo(sender)
      sender ! future
  }

  override def receive: Receive = closed(0)

}
