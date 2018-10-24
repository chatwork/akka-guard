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
    config.backoff match {
      case b: ExponentialBackoff =>
        new SABActor[T, R, ExponentialBackoff](
          id,
          maxFailures = config.maxFailures,
          failureTimeout = config.failureTimeout,
          failedResponse = failedResponse,
          isFailed = isFailed,
          eventHandler = eventHandler
        )(b) with ExponentialBackoffActor
      case b: LinealBackoff =>
        new SABActor[T, R, LinealBackoff](
          id,
          maxFailures = config.maxFailures,
          failureTimeout = config.failureTimeout,
          failedResponse = failedResponse,
          isFailed = isFailed,
          eventHandler = eventHandler
        )(b) with LinealBackoffActor
    }
  )

  def name(id: String): String = s"SABlocker-$id"

  private[guard] case object FailureTimeout

  case class GetAttemptRequest(id: ID)
  case class GetAttemptResponse(id: ID, attempt: Long)

  case object GetStatus

  private[guard] case class Failed(failedCount: Long)

  private[guard] case class BecameClosed(attempt: Long, count: Long, setTimer: Boolean)

  trait FailureTimeoutSchedule {
    protected def createFailureTimeoutSchedule(): Unit
  }

  trait BecomeClosedActor {
    protected def becomeClosed(attempt: Long, failureCount: Long, fireEventHandler: Boolean = true): Unit
  }

}

import com.chatwork.akka.guard.SABActor._

class SABActor[T, R, B <: Backoff](
    id: ID,
    maxFailures: Long,
    failureTimeout: FiniteDuration,
    failedResponse: => Try[R],
    isFailed: R => Boolean,
    eventHandler: Option[(ID, SABStatus) => Unit]
)(
    override protected val backoff: B,
) extends Actor
    with ActorLogging
    with FailureTimeoutSchedule
    with BecomeClosedActor {
  _: BackoffActor[B] =>

  import context.dispatcher

  type Message = SABMessage[T, R]

  override def preStart: Unit = {
    log.debug("preStart")
    createFailureTimeoutSchedule()
  }

  override def postStop(): Unit = {
    log.debug("postStop")
    failureTimeoutCancel.cancel()
    closeCancel.foreach(_.cancel())
    super.postStop()
  }

  private var failureTimeoutCancel: Cancellable = _
  private var closeCancel: Option[Cancellable]  = None

  override protected def createFailureTimeoutSchedule(): Unit = {
    failureTimeoutCancel = context.system.scheduler.scheduleOnce(failureTimeout, self, FailureTimeout)
  }

  private def reply(future: Future[R]) = future.pipeTo(sender)

  private val open: Receive = {
    case GetStatus      => sender ! SABStatus.Open // For debugging
    case FailureTimeout =>
    case _: Message     => reply(Future.fromTry(failedResponse))
  }

  private def commonWithOpen(attempt: Long, failureCount: Long) = common(attempt, failureCount) orElse open

  private def becomeOpen(attempt: Long, failureCount: Long): Unit = {
    log.debug("become an open")
    context.become(commonWithOpen(attempt, failureCount))
    closeCancel = createResetBackoffSchedule(attempt)
    eventHandler.foreach(_.apply(id, SABStatus.Open))
  }

  override protected def becomeClosed(attempt: Long, failureCount: Long, fireEventHandler: Boolean = true): Unit = {
    log.debug(s"become a closed to $failureCount")
    context.become(commonWithClosed(attempt, failureCount))
    if (fireEventHandler)
      eventHandler.foreach(_.apply(id, SABStatus.Closed))
  }

  private val isBoundary: Long => Boolean = _ > this.maxFailures

  private def fail(attempt: Long, failureCount: Long): Unit = {
    val count = failureCount + 1
    log.debug("failure count is [{}].", count)
    becomeClosed(attempt, count, fireEventHandler = false)
    if (isBoundary(count)) self ! FailureTimeout
  }

  private def common(attempt: Long, failureCount: Long): Receive = {
    case GetAttemptRequest(_id) =>
      require(_id == id)
      sender() ! GetAttemptResponse(id, attempt)
    case BecameClosed(_attempt, _count, b) =>
      if (b) createFailureTimeoutSchedule()
      becomeClosed(_attempt, _count, fireEventHandler = false)
  }

  private def closed(attempt: Long, failureCount: Long): Receive = {
    case GetStatus => sender ! SABStatus.Closed // For debugging

    case FailureTimeout =>
      if (isBoundary(failureCount))
        becomeOpen(attempt + 1, failureCount)
      else
        reset(attempt)

    case Failed(failedCount) => fail(attempt, failedCount)
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
        case Success(r) if !isFailed(r) => self ! BecameClosed(attempt, 0, setTimer = false)
      }
      reply(future)

  }

  private def commonWithClosed(attempt: Long, failureCount: Long) =
    common(attempt, failureCount) orElse closed(attempt, failureCount)

  override def receive: Receive = commonWithClosed(0, 0)

}
