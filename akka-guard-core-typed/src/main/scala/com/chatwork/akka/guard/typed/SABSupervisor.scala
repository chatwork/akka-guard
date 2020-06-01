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

  final case class SABSupervisorMessage[T, R](value: SABMessage[T, R]) extends Command {
    lazy val id: String = value.id
  }

  def name(id: String): String = s"SABSupervisor-$id"

  def apply[T, R](config: SABConfig)(behaviorF: SABMessage[T, R] => Behavior[SABActor.Command]): Behavior[Command] =
    Behaviors
      .setup[AnyRef] { context =>
        config.guardResetTimeout.foreach(d => context.setReceiveTimeout(d.toMillis.milli, ReceiveTimeout))

        Behaviors.receiveMessage {
          case ReceiveTimeout =>
            context.log.debug("receive timeout")
            Behaviors.stopped

          case SABSupervisorMessage(msg) =>
            val message          = msg.asInstanceOf[SABMessage[T, R]]
            val commandForwarder = CommandForwarder[SABActor.Command, SABMessage[T, R]](context)
            val childName        = SABActor.name(msg.id)
            context
              .child(childName)
              .fold(commandForwarder.createAndForward(message, behaviorF(message), childName))(a =>
                commandForwarder.forwardMsg(message)(a.asInstanceOf[ActorRef[SABActor.Command]])
              )
            Behaviors.same
        }
      }.narrow[Command]

  def apply[T, R](
      config: SABConfig,
      failedResponse: => Try[R],
      isFailed: R => Boolean,
      eventHandler: Option[(ID, SABStatus) => Unit] = None
  ): Behavior[Command] = {
    lazy val behaviorF: SABMessage[T, R] => Behavior[SABActor.Command] = message =>
      SABActor[T, R](message.id, config, failedResponse, isFailed, eventHandler)
    apply(config)(behaviorF)
  }

}
