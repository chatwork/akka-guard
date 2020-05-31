package com.chatwork.akka.guard.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import com.chatwork.akka.guard.typed.SABActor.{ SABMessage, SABStatus }
import com.chatwork.akka.guard.typed.config.SABConfig

import scala.concurrent.duration._
import scala.util.Try

object SABSupervisor {

  sealed trait Command
  case object ReceiveTimeout extends Command

  final case class SABSuperVisorMessage[T, R](value: SABMessage[T, R]) extends Command {
    lazy val id: String = value.id
  }

  def name(id: String): String = s"SABSupervisor-$id"

  def apply[T, R](
      id: String,
      config: SABConfig,
      failedResponse: => Try[R],
      isFailed: R => Boolean,
      eventHandler: Option[(ID, SABStatus) => Unit] = None
  ): Behavior[Command] =
    Behaviors
      .setup[AnyRef] { context =>
        val commandForwarder = CommandForwarder[SABActor.Command, SABMessage[T, R]](context)
        val behavior         = SABActor[T, R](id, config, failedResponse, isFailed, eventHandler)

        config.guardResetTimeout.foreach(d => context.setReceiveTimeout(d.toMillis.milli, ReceiveTimeout))

        Behaviors.receiveMessage {
          case ReceiveTimeout =>
            context.log.debug("receive timeout")
            Behaviors.stopped
          case SABSuperVisorMessage(msg) =>
            val message = msg.asInstanceOf[SABMessage[T, R]]
            context
              .child(SABActor.name(msg.id))
              .fold(commandForwarder.createAndForward(message, behavior, SABActor.name(msg.id)))(a =>
                commandForwarder.forwardMsg(message)(a.asInstanceOf[ActorRef[SABActor.Command]])
              )
            Behaviors.same
        }
      }.narrow[Command]

}
