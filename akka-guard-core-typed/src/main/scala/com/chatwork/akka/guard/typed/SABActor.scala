package com.chatwork.akka.guard.typed

import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import com.chatwork.akka.guard.typed.config.{ AutoReset, ExponentialBackoff, LinealBackoff, SABConfig }
import enumeratum._

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

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

  final case class GetAttemptRequest(id: ID, replyTo: ActorRef[GetAttemptResponse]) extends Command
  final case class GetStatus(replyTo: ActorRef[SABStatus])                          extends Command

  private[typed] final case class BecameClosed(attempt: Long, count: Long, setTimer: Boolean) extends Command
  private[typed] case object FailureTimeout                                                   extends Command
  private[typed] final case class Failed(failedCount: Long)                                   extends Command

  case class SABMessage[T, R](id: String, request: T, handler: T => Future[R], replyTo: ActorRef[Try[R]])
      extends Command {
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

  val SABActorServiceKey: ServiceKey[Command] = ServiceKey[Command]("SABActor") // For debugging

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
      context.system.receptionist ! Receptionist.Register(SABActorServiceKey, context.self)
      createFailureTimeoutSchedule()
    }

    private def postStop(): Behavior[Command] = {
      context.log.debug("postStop")
      context.system.receptionist ! Receptionist.Deregister(SABActorServiceKey, context.self)
      timers.cancel(FailureTimeoutCancelKey)
      timers.cancel(CloseCancelKey)
      Behaviors.same
    }

    protected def createFailureTimeoutSchedule(): Unit =
      timers.startSingleTimer(FailureTimeoutCancelKey, FailureTimeout, failureTimeout)

    private def reply(future: Future[R], reply: ActorRef[Try[R]]): Behavior[Command] = {
      import akka.actor.typed.scaladsl.adapter._
      import akka.pattern.pipe
      pipe(future)(context.executionContext).to(reply.toClassic)
      Behaviors.same
    }

    private def open: PartialFunction[Command, Behavior[Command]] = {
      case GetStatus(reply) =>
        reply ! SABStatus.Open // For debugging
        Behaviors.same
      case FailureTimeout => Behaviors.same
      case msg: Message   => reply(Future.fromTry(failedResponse), msg.replyTo)
    }

    private def commonWithOpen(attempt: Long): Behavior[Command] = {
      Behaviors
        .receiveMessagePartial(common(attempt) orElse open)
        .receiveSignal { case (_, PostStop) =>
          postStop()
        }
    }

    private def becomeOpen(attempt: Long): Behavior[Command] = {
      context.log.debug("become an open")
      createResetBackoffSchedule(attempt)(CloseCancelKey)
      eventHandler.foreach(_.apply(id, SABStatus.Open)) // FIXME become後に送りたい
      commonWithOpen(attempt)
    }

    protected def becomeClosed(attempt: Long, failureCount: Long): Behavior[Command] = {
      context.log.debug(s"become a closed to $failureCount")
      eventHandler.foreach(_.apply(id, SABStatus.Closed)) // FIXME become後に送りたい
      commonWithClosed(attempt, failureCount)
    }

    private val isBoundary: Long => Boolean = _ > this.maxFailures

    private def fail(attempt: Long, failureCount: Long): Behavior[Command] = {
      val count = failureCount + 1
      context.log.debug("failure count is [{}].", count)
      if (isBoundary(count)) context.self ! FailureTimeout // FIXME become後に送りたい
      becomeClosed(attempt, count)
    }

    private def common(attempt: Long): PartialFunction[Command, Behavior[Command]] = {
      case GetAttemptRequest(_id, reply) =>
        require(_id == id)
        reply ! GetAttemptResponse(_id, attempt)
        Behaviors.same
      case BecameClosed(_attempt, _count, b) =>
        if (b) createFailureTimeoutSchedule()
        becomeClosed(_attempt, _count)
      case StopCommand =>
        context.log.debug("stop command.")
        Behaviors.stopped
    }

    private def closed(attempt: Long, failureCount: Long): PartialFunction[Command, Behavior[Command]] = {
      case GetStatus(reply) =>
        reply ! SABStatus.Closed // For debugging
        Behaviors.same
      case FailureTimeout =>
        if (isBoundary(failureCount))
          becomeOpen(attempt + 1)
        else
          reset(attempt)
      case Failed(failedCount) => fail(attempt, failedCount)
      case msg: Message =>
        val future =
          try {
            msg.execute
          } catch {
            case NonFatal(cause) => Future.failed(cause)
          }
        future.onComplete {
          case Failure(_)                => context.self ! Failed(failureCount)
          case Success(r) if isFailed(r) => context.self ! Failed(failureCount)
          case Success(r)                => context.self ! BecameClosed(attempt, 0, setTimer = false)
        }(context.executionContext)
        reply(future, msg.replyTo)
    }

    private def commonWithClosed(attempt: Long, failureCount: Long): Behavior[Command] = {
      Behaviors
        .receiveMessagePartial(common(attempt) orElse closed(attempt, failureCount))
        .receiveSignal { case (_, PostStop) =>
          postStop()
        }
    }

    def behavior: Behavior[Command] = {
      preStart()
      commonWithClosed(0, 0)
    }
  }

  class ExponentialBackoffActor[T, R](
      id: ID,
      maxFailures: Long,
      backoff: ExponentialBackoff,
      failureTimeout: FiniteDuration,
      failedResponse: => Try[R],
      isFailed: R => Boolean,
      eventHandler: Option[(ID, SABStatus) => Unit]
  )(implicit
      context: ActorContext[Command],
      timers: TimerScheduler[Command]
  ) extends SABActor[T, R](id, maxFailures, failureTimeout, failedResponse, isFailed, eventHandler) {

    protected def createScheduler(delay: FiniteDuration, attempt: Long)(key: Any): Unit =
      timers.startSingleTimer(key, BecameClosed(attempt, 0, setTimer = true), delay)

    private def createResetBackoffSchedule(key: Any): Unit = {
      backoff.backoffReset match {
        case AutoReset(resetBackoff) =>
          createScheduler(resetBackoff, 0)(key)
        case _ =>
          ()
      }
    }

    override protected def createResetBackoffSchedule(attempt: Long)(key: Any): Unit = {
      val d = backoff.toDuration(attempt)
      if (backoff.maxBackoff <= d)
        createResetBackoffSchedule(key)
      else
        createScheduler(d, attempt)(key)
    }

    override protected def reset(attempt: Long): Behavior[Command] = {
      createFailureTimeoutSchedule()
      becomeClosed(attempt, failureCount = 0)
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
    ): Behavior[Command] =
      Behaviors.setup { implicit context =>
        Behaviors.withTimers { implicit timers =>
          val actor = new ExponentialBackoffActor[T, R](
            id,
            maxFailures,
            backoff,
            failureTimeout,
            failedResponse,
            isFailed,
            eventHandler
          )
          actor.behavior
        }

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
  )(implicit
      context: ActorContext[Command],
      timers: TimerScheduler[Command]
  ) extends SABActor[T, R](id, maxFailures, failureTimeout, failedResponse, isFailed, eventHandler) {

    override protected def createResetBackoffSchedule(attempt: Long)(key: Any): Unit =
      timers.startSingleTimer(key, StopCommand, backoff.toDuration(attempt))

    override protected def reset(attempt: Long): Behavior[Command] = {
      context.log.debug("reset")
      Behaviors.stopped
    }
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
          val actor = new LinealBackoffActor[T, R](
            id,
            maxFailures,
            backoff,
            failureTimeout,
            failedResponse,
            isFailed,
            eventHandler
          )
          actor.behavior
        }
      }
  }
}
