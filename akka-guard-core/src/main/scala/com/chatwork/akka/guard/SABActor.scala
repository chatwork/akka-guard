package com.chatwork.akka.guard

import akka.actor._
import akka.pattern.pipe

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object SABActor {

  def props[T, R](id: String,
                  config: SABConfig,
                  failedResponse: => Try[R],
                  isFailed: R => Boolean,
                  eventHandler: Option[(ID, SABStatus) => Unit] = None): Props = Props(
    config.backoff match {
      case b: ExponentialBackoff =>
        new ExponentialBackoffActor[T, R](
          id,
          maxFailures = config.maxFailures,
          backoff = b,
          failureTimeout = config.failureDuration,
          failedResponse = failedResponse,
          isFailed = isFailed,
          eventHandler = eventHandler
        )
      case b: LinealBackoff =>
        new LinealBackoffActor[T, R](
          id,
          maxFailures = config.maxFailures,
          backoff = b,
          failureTimeout = config.failureDuration,
          failedResponse = failedResponse,
          isFailed = isFailed,
          eventHandler = eventHandler
        )
    }
  )

  def name(id: String): String = s"SABlocker-$id"

  case class GetAttemptRequest(id: ID)
  case class GetAttemptResponse(id: ID, attempt: Long)

  case object GetStatus

  private[guard] case object FailureTimeout

  private[guard] case class Failed(failedCount: Long)

  private[guard] case class BecameClosed(attempt: Long, count: Long, setTimer: Boolean)

}

sealed abstract class SABActor[T, R](
    id: ID,
    maxFailures: Long,
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
    log.debug("preStart")
    createFailureTimeoutSchedule()
  }

  override def postStop(): Unit = {
    log.debug("postStop")
    failureTimeoutCancel.cancel()
    closeCancel.foreach(_.cancel())
    super.postStop()
  }

  protected def createResetBackoffSchedule(attempt: Long): Option[Cancellable]
  protected def reset(attempt: Long): Unit

  private var failureTimeoutCancel: Cancellable = _
  private var closeCancel: Option[Cancellable]  = None

  protected def createFailureTimeoutSchedule(): Unit = {
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

  protected def becomeClosed(attempt: Long, failureCount: Long, fireEventHandler: Boolean = true): Unit = {
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

class ExponentialBackoffActor[T, R](
    id: ID,
    maxFailures: Long,
    backoff: ExponentialBackoff,
    failureTimeout: FiniteDuration,
    failedResponse: => Try[R],
    isFailed: R => Boolean,
    eventHandler: Option[(ID, SABStatus) => Unit]
) extends SABActor[T, R](id, maxFailures, failureTimeout, failedResponse, isFailed, eventHandler) {
  import SABActor._
  import context.dispatcher

  protected def createScheduler(delay: FiniteDuration, attempt: Long): Cancellable =
    context.system.scheduler.scheduleOnce(delay, self, BecameClosed(attempt, 0, setTimer = true))

  private def createResetBackoffSchedule(): Option[Cancellable] = {
    backoff.backoffReset match {
      case AutoReset(resetBackoff) =>
        Some(createScheduler(resetBackoff, 0))
      case _ =>
        None
    }
  }

  override protected def createResetBackoffSchedule(attempt: Long): Option[Cancellable] = {
    val d = backoff.toDuration(attempt)
    if (backoff.maxBackoff <= d)
      createResetBackoffSchedule()
    else
      Some(createScheduler(d, attempt))
  }

  override protected def reset(attempt: Long): Unit = {
    createFailureTimeoutSchedule()
    becomeClosed(attempt, failureCount = 0, fireEventHandler = false)
  }
}

class LinealBackoffActor[T, R](
    id: ID,
    maxFailures: Long,
    backoff: LinealBackoff,
    failureTimeout: FiniteDuration,
    failedResponse: => Try[R],
    isFailed: R => Boolean,
    eventHandler: Option[(ID, SABStatus) => Unit]
) extends SABActor[T, R](id, maxFailures, failureTimeout, failedResponse, isFailed, eventHandler) {
  import context.dispatcher

  override protected def createResetBackoffSchedule(attempt: Long): Option[Cancellable] =
    Some(context.system.scheduler.scheduleOnce(backoff.toDuration(attempt), self, PoisonPill))

  override protected def reset(attempt: Long): Unit = {
    context.stop(self)
  }
}
