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
      id,
      maxFailures = config.maxFailures,
      backoff = config.backoff,
      failureTimeout = config.failureTimeout,
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
    backoff: Backoff,
    failureTimeout: FiniteDuration,
    failedResponse: => Try[R],
    isFailed: R => Boolean,
    eventHandler: Option[(ID, SABStatus) => Unit]
) extends Actor
    with ActorLogging {

  import SABActor._
  import context.dispatcher

  type Message = SABMessage[T, R]

  override def preStart: Unit = {
    createFailureTimeoutSchedule
  }

  private def createResetBackoffSchedule: Unit = {
    backoff match {
      case b: ExponentialBackoff =>
        b.backoffReset match {
          case AutoReset(resetBackoff) =>
            context.system.scheduler.scheduleOnce(resetBackoff) {
              becomeClosed(attempt = 0, failureCount = 0)
            }
          case _ =>
        }
      case _ =>
    }
  }

  private def createFailureTimeoutSchedule: Unit = {
    context.system.scheduler.scheduleOnce(failureTimeout, self, Tick)
  }

  private def reply(future: Future[R]) = future.pipeTo(sender)

  private val open: Receive = {
    case GetStatus  => sender ! SABStatus.Open // For debugging
    case Tick       =>
    case _: Message => reply(Future.fromTry(failedResponse))
  }

  private def becomeOpen(attempt: Long): Unit = {
    log.debug("become an open")
    context.become(open)

    if (backoff.strategy == SABBackoffStrategy.Exponential && attempt == 1)
      createResetBackoffSchedule

    context.system.scheduler.scheduleOnce(backoff.toDuration(attempt)) {
      backoff.strategy match {
        case SABBackoffStrategy.Lineal =>
          self ! PoisonPill
        case SABBackoffStrategy.Exponential =>
          createFailureTimeoutSchedule
          becomeClosed(attempt, failureCount = 0)
      }
    }

    eventHandler.foreach(_.apply(id, SABStatus.Open))
  }

  private def becomeClosed(attempt: Long, failureCount: Long, fireEventHandler: Boolean = true): Unit = {
    log.debug(s"become a closed to $failureCount")
    context.become(closed(attempt, failureCount))
    if (fireEventHandler)
      eventHandler.foreach(_.apply(id, SABStatus.Closed))
  }

  private val isBoundary: Long => Boolean = _ > this.maxFailures

  private def fail(attempt: Long, failureCount: Long): Unit = {
    val count = failureCount + 1
    log.debug("failure count is [{}].", count)
    becomeClosed(attempt, count, fireEventHandler = false)
    if (isBoundary(count)) self ! Tick
  }

  private def closed(attempt: Long, failureCount: Long): Receive = {
    case GetStatus => sender ! SABStatus.Closed // For debugging

    case Tick =>
      if (isBoundary(failureCount))
        becomeOpen(attempt + 1)
      else
        backoff.strategy match {
          case SABBackoffStrategy.Lineal =>
            context.stop(self)
          case SABBackoffStrategy.Exponential =>
            createFailureTimeoutSchedule
        }

    case Failed(failedCount) => fail(attempt, failedCount)
    case BecameClosed(count) => becomeClosed(attempt, count, fireEventHandler = false)
    case ManualReset         => becomeClosed(attempt = 0, failureCount = failureCount, fireEventHandler = false)

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

  override def receive: Receive = closed(attempt = 0, failureCount = 0)

}
