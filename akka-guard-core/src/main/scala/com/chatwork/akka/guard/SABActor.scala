package com.chatwork.akka.guard

import akka.actor._
import akka.pattern.pipe

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object SABActor {

  def props[T, R](id: String,
                  config: SABBrokerConfig,
                  failedResponse: Try[R],
                  isFailed: R => Boolean,
                  eventHandler: Option[(ID, SABStatus) => Unit] = None): Props = Props(
    new SABActor[T, R](
      id = id,
      maxFailures = config.maxFailures,
      failureTimeout = config.failureTimeout,
      resetTimeout = config.resetTimeout,
      failedResponse = failedResponse,
      isFailed = isFailed,
      eventHandler = eventHandler
    )
  )

  def name(id: String): String = s"SABlocker-$id"

  private[guard] case object Tick
  case object GetStatus
  private[guard] case class Failed(failedCount: Long)
  private[guard] case class BecameClosed(count: Long)
}

class SABActor[T, R](
    id: ID,
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration,
    failedResponse: => Try[R],
    isFailed: R => Boolean,
    eventHandler: Option[(ID, SABStatus) => Unit]
) extends Actor
    with ActorLogging {
  import SABActor._
  import context.dispatcher

  type Message = SABMessage[T, R]

  override def preStart: Unit = {
    createSchedule
  }

  private def createSchedule: Cancellable =
    context.system.scheduler.scheduleOnce(failureTimeout, self, Tick)

  private def reply(future: Future[R]) = future.pipeTo(sender)

  private val open: Receive = {
    case GetStatus  => sender ! SABStatus.Open // For debugging
    case Tick       =>
    case _: Message => reply(Future.fromTry(failedResponse))
  }

  private def becomeOpen(): Unit = {
    log.debug("become an open")
    context.become(open)
    context.system.scheduler.scheduleOnce(resetTimeout)(context.stop(self))
    eventHandler.foreach(_.apply(id, SABStatus.Open))
  }

  private def becomeClosed(count: Long): Unit = {
    log.debug(s"become a closed to $count")
    context.become(closed(count))
    eventHandler.foreach(_.apply(id, SABStatus.Closed))
  }

  private val isBoundary: Long => Boolean = _ > this.maxFailures

  private def fail(failureCount: Long): Unit = {
    val count = failureCount + 1
    log.debug("failure count is [{}].", count)
    becomeClosed(count)
    if (isBoundary(count)) self ! Tick
  }

  private def closed(failureCount: Long): Receive = {
    case GetStatus => sender ! SABStatus.Closed // For debugging

    case Tick =>
      if (isBoundary(failureCount))
        becomeOpen()
      else
        context.stop(self)

    case Failed(failedCount) => fail(failedCount)
    case BecameClosed(count) => becomeClosed(count)

    case msg: Message =>
      val future = try {
        msg.execute
      } catch {
        case NonFatal(cause) => Future.failed(cause)
      }
      future.onComplete {
        case Failure(_)                 => self ! Failed(failureCount)
        case Success(r) if isFailed(r)  => self ! Failed(failureCount)
        case Success(r) if !isFailed(r) => self ! BecameClosed(0)
      }
      reply(future)

  }

  override def receive: Receive = closed(0)

}
