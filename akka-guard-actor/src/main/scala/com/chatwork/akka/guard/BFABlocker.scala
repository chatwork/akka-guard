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
      isFailed = config.isFailed,
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
    isFailed: R => Boolean,
    receiveTimeout: Option[Duration] = None,
    eventHandler: Option[BFABlockerStatus => Unit] = None
) extends Actor
    with ActorLogging {
  import BFABlocker._
  import context.dispatcher

  type Message = BFAMessage[T, R]

  receiveTimeout.foreach(context.setReceiveTimeout)

  override def preStart: Unit = {
    createSchedule
  }

  private def createSchedule: Cancellable =
    context.system.scheduler.scheduleOnce(failureTimeout, self, Tick)

  private val open: Receive = {
    case GetStatus  => sender ! BFABlockerStatus.Open // For debugging
    case Tick       =>
    case _: Message => sender ! Future.fromTry(failedResponse)
  }

  private val isFailover: Long => Boolean = _ > this.maxFailures

  private def doFail(count: Long): Unit = {
    log.debug("failure count is [{}].", count)
    context.become(closed(count))
    if (isFailover(count)) self ! Tick
  }

  private def fail(failureCount: Long): Unit = doFail(failureCount + 1)

  private def reset(): Unit = {
    createSchedule
    log.debug("become a closed to 0")
    context.become(closed(0))
  }

  private def becomeOpen(): Unit = {
    log.debug("become an open")
    context.become(open)
    context.system.scheduler.scheduleOnce(resetTimeout)(reset())
  }

  private def closed(failureCount: Long): Receive = {
    case GetStatus => sender ! BFABlockerStatus.Closed // For debugging

    case Tick =>
      if (isFailover(failureCount)) {
        becomeOpen()
      } else {
        reset()
      }

    case msg: Message =>
      val future = try {
        msg.execute
      } catch {
        case NonFatal(cause) => Future.failed(cause)
      }
      future.onComplete {
        case Failure(_)                 => fail(failureCount)
        case Success(r) if isFailed(r)  => fail(failureCount)
        case Success(r) if !isFailed(r) => context.become(closed(0))
      }
      sender ! future
  }

  override def receive: Receive = closed(0)

}
