package com.chatwork.akka.guard.typed

import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import enumeratum._

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

object SABActor {

  case class GetAttemptResponse(id: ID, attempt: Long)

  sealed abstract class SABStatus(override val entryName: String) extends EnumEntry

  object SABStatus extends Enum[SABStatus] {
    override def values: immutable.IndexedSeq[SABStatus] = findValues
    case object Open   extends SABStatus("open")
    case object Closed extends SABStatus("close")
  }

  //

  sealed trait Command

  case class GetAttemptRequest(id: ID, replyTo: ActorRef[GetAttemptResponse]) extends Command
  case class GetStatus(replyTo: ActorRef[SABStatus])                          extends Command

  private[typed] case class BecameClosed(attempt: Long, count: Long, setTimer: Boolean) extends Command
  private[typed] case object FailureTimeout                                             extends Command
  private[typed] case class Failed(failedCount: Long)                                   extends Command

  sealed trait BackoffReset
  case object ManualReset                                  extends BackoffReset
  final case class AutoReset(resetBackoff: FiniteDuration) extends BackoffReset

  case class SABMessage[T, R](id: String, request: T, handler: T => Future[R]) extends Command {
    def execute: Future[R] = handler(request)
  }

  case object StopCommand extends Command

  def name(id: String): String = s"SABlocker-$id"

  def apply[T, R](
      id: String,
      config: SABConfig,
      failedResponse: => Try[R],
      isFailed: R => Boolean,
      eventHandler: Option[(ID, SABStatus) => Unit] = None
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      config.backoff match {
        case exBackoff: ExponentialBackoff =>
          ExponentialBackoffActor[T, R](
            id,
            maxFailures = config.maxFailures,
            backoff = exBackoff,
            failureTimeout = config.failureDuration,
            failedResponse,
            isFailed,
            eventHandler
          )
        case linBackoff: LinealBackoff =>
          LinealBackoffActor[T, R](
            id,
            maxFailures = config.maxFailures,
            backoff = linBackoff,
            failureTimeout = config.failureDuration,
            failedResponse,
            isFailed,
            eventHandler
          )
      }
    }

  abstract class SABActor[T, R](
      id: ID,
      maxFailures: Long,
      failureTimeout: FiniteDuration,
      failedResponse: => Try[R],
      isFailed: R => Boolean,
      eventHandler: Option[(ID, SABStatus) => Unit]
  )(implicit
      context: ActorContext[Command],
      timers: TimerScheduler[Command]
  ) {
    type Message = SABMessage[T, R]
    private val FailureTimeoutCancelKey: String = "FailureTimeoutCancel"
    private val CloseCancelKey: String          = "CloseCancel"

    protected def createResetBackoffSchedule(attempt: Long)(key: Any = CloseCancelKey): Unit
    protected def reset(attempt: Long): Behavior[Command]

    private def preStart(): Unit = {
      context.log.debug("preStart")
      createFailureTimeoutSchedule()
    }

    private def postStop(): Behavior[Command] = {
      context.log.debug("postStop")
      timers.cancel(FailureTimeoutCancelKey)
      timers.cancel(CloseCancelKey)
      Behaviors.same
    }

    private def createFailureTimeoutSchedule(): Unit =
      timers.startSingleTimer(FailureTimeoutCancelKey, FailureTimeout, failureTimeout)

    private def reply(future: Future[R]): Behavior[Command] = {
      context.pipeToSelf(future)(a => ???)
      Behaviors.same
    }

    private def open(attempt: Long): Behavior[Command] = {
      Behaviors
        .receiveMessagePartial(common(attempt) orElse {
          case GetStatus(reply) =>
            reply ! SABStatus.Open // For debugging
            Behaviors.same
          case FailureTimeout => Behaviors.same
          case _: Message     => reply(Future.fromTry(failedResponse))
        }).receiveSignal {
          case (_, PostStop) => postStop()
        }
    }

    private def becomeOpen(attempt: Long): Behavior[Command] = {
      context.log.debug("become an open")
      createResetBackoffSchedule(attempt)(CloseCancelKey)
      eventHandler.foreach(_.apply(id, SABStatus.Open)) // FIXME become後に送りたい
      open(attempt)
    }

    private def becomeClosed(attempt: Long, failureCount: Long, fireEventHandler: Boolean = true): Behavior[Command] = {
      context.log.debug(s"become a closed to $failureCount")
      if (fireEventHandler)
        eventHandler.foreach(_.apply(id, SABStatus.Closed)) // FIXME become後に送りたい
      closed(attempt, failureCount)
    }

    private val isBoundary: Long => Boolean = _ > this.maxFailures

    private def fail(attempt: Long, failureCount: Long): Behavior[Command] = {
      val count = failureCount + 1
      context.log.debug("failure count is [{}].", count)
      if (isBoundary(count)) context.self ! FailureTimeout // FIXME become後に送りたい
      becomeClosed(attempt, count, fireEventHandler = false)
    }

    private def common(attempt: Long): PartialFunction[Command, Behavior[Command]] = {
      case GetAttemptRequest(_id, reply) =>
        require(_id == id)
        reply ! GetAttemptResponse(_id, attempt)
        Behaviors.same
      case BecameClosed(_attempt, _count, b) =>
        if (b) createFailureTimeoutSchedule()
        becomeClosed(_attempt, _count, fireEventHandler = false)
      case StopCommand => Behaviors.stopped
    }

    private def closed(attempt: Long, failureCount: Long): Behavior[Command] = {
      import context.executionContext
      Behaviors
        .receiveMessagePartial(common(attempt) orElse {
          case GetStatus(reply) =>
            reply ! SABStatus.Closed // For debugging
            Behaviors.same
          case FailureTimeout =>
            if (isBoundary(failureCount)) becomeOpen(attempt + 1)
            else reset(attempt)
          case Failed(failedCount) => fail(attempt, failedCount)
//          case ManualReset => becomeClosedF(0, failureCount, false)
          case msg: Message =>
            val future =
              try {
                msg.execute
              } catch {
                case NonFatal(cause) => Future.failed(cause)
              }
            future.onComplete {
              case Failure(_)                 => context.self ! Failed(failureCount)
              case Success(r) if isFailed(r)  => context.self ! Failed(failureCount)
              case Success(r) if !isFailed(r) => context.self ! BecameClosed(attempt, 0, setTimer = false)
            }
            reply(future)
        }).receiveSignal {
          case (_, PostStop) => postStop()
        }
    }

    def behavior: Behavior[Command] = {
      preStart()
      closed(0, 0)
    }
  }

  object ExponentialBackoffActor {

    def apply[T, R](
        id: ID,
        maxFailures: Long,
        backoff: ExponentialBackoff,
        failureTimeout: FiniteDuration,
        failedResponse: => Try[R],
        isFailed: R => Boolean,
        eventHandler: Option[(ID, SABStatus) => Unit]
    ): Behavior[Command] = ???
  }

  object LinealBackoffActor {

    def apply[T, R](
        id: ID,
        maxFailures: Long,
        backoff: LinealBackoff,
        failureTimeout: FiniteDuration,
        failedResponse: => Try[R],
        isFailed: R => Boolean,
        eventHandler: Option[(ID, SABStatus) => Unit]
    ): Behavior[Command] =
      Behaviors.setup { implicit context =>
        Behaviors.withTimers { implicit timers =>
          val actor = new SABActor[T, R](id, maxFailures, failureTimeout, failedResponse, isFailed, eventHandler) {

            override protected def createResetBackoffSchedule(attempt: Long)(key: Any): Unit =
              timers.startSingleTimer(key, StopCommand, backoff.toDuration(attempt))

            override protected def reset(attempt: Long): Behavior[Command] = Behaviors.stopped
          }
          actor.behavior
        }
      }
  }
}
