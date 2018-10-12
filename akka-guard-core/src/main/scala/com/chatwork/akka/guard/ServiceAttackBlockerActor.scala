package com.chatwork.akka.guard

import akka.actor._
import akka.pattern.pipe

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object ServiceAttackBlockerActor {

  def props[T, R](id: String,
                  config: SABBrokerConfig,
                  failedResponse: Try[R],
                  isFailed: R => Boolean,
                  eventHandler: Option[(ID, ServiceAttackBlockerStatus) => Unit] = None): Props = Props(
    new ServiceAttackBlockerActor[T, R](
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
}

class ServiceAttackBlockerActor[T, R](
    id: ID,
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    resetTimeout: FiniteDuration,
    failedResponse: => Try[R],
    isFailed: R => Boolean,
    eventHandler: Option[(ID, ServiceAttackBlockerStatus) => Unit]
) extends Actor
    with ActorLogging {
  import ServiceAttackBlockerActor._
  import context.dispatcher

  type Message = SABMessage[T, R]

  override def preStart: Unit = {
    createSchedule
  }

  private def createSchedule: Cancellable =
    context.system.scheduler.scheduleOnce(failureTimeout, self, Tick)

  private def reply(future: Future[R]) = future.pipeTo(sender)

  private val open: Receive = {
    case GetStatus      => sender ! ServiceAttackBlockerStatus.Open // For debugging
    case Tick           =>
    case _: Message     => reply(Future.fromTry(failedResponse))
  }

  private def becomeOpen(): Unit = {
    log.debug("become an open")
    context.become(open)
    context.system.scheduler.scheduleOnce(resetTimeout)(context.stop(self))
    eventHandler.foreach(_.apply(id, ServiceAttackBlockerStatus.Open))
  }

  private def becomeClosed(count: Long): Unit = {
    log.debug(s"become a closed to $count")
    context.become(closed(count))
    eventHandler.foreach(_.apply(id, ServiceAttackBlockerStatus.Closed))
  }

  private val isBoundary: Long => Boolean = _ > this.maxFailures

  private def fail(failureCount: Long): Unit = {
    val count = failureCount + 1
    log.debug("failure count is [{}].", count)
    becomeClosed(count)
    if (isBoundary(count)) self ! Tick
  }

  private def closed(failureCount: Long): Receive = {
    case GetStatus => sender ! ServiceAttackBlockerStatus.Closed // For debugging

    case Tick =>
      if (isBoundary(failureCount))
        becomeOpen()
      else
        context.stop(self)

    case msg: Message =>
      val future = try {
        msg.execute
      } catch {
        case NonFatal(cause) => Future.failed(cause)
      }
      future.onComplete {
        case Failure(_)                 => fail(failureCount)
        case Success(r) if isFailed(r)  => fail(failureCount)
        case Success(r) if !isFailed(r) => becomeClosed(0)
      }
      reply(future)

  }

  override def receive: Receive = closed(0)

}
