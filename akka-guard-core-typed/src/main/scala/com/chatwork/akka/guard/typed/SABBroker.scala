package com.chatwork.akka.guard.typed

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import com.chatwork.akka.guard.typed.SABActor.{ SABMessage, SABStatus }
import com.chatwork.akka.guard.typed.SABSupervisor.SABSupervisorMessage
import com.chatwork.akka.guard.typed.config.SABConfig

import scala.util.Try

object SABBroker {

  sealed trait Command
  final case class SABBrokerMessage[T, R](value: SABMessage[T, R]) extends Command

  def apply[T, R](behavior: Behavior[SABSupervisor.Command]): Behavior[Command] =
    Behaviors
      .setup[AnyRef] { context =>
        Behaviors.receiveMessage { case SABBrokerMessage(msg) =>
          val message          = SABSupervisorMessage(msg.asInstanceOf[SABMessage[T, R]])
          val commandForwarder = CommandForwarder[SABSupervisor.Command, SABSupervisorMessage[T, R]](context)
          val childName        = SABSupervisor.name(message.id)
          context
            .child(childName)
            .fold(commandForwarder.createAndForward(message, behavior, childName))(a =>
              commandForwarder.forwardMsg(message)(a.asInstanceOf[ActorRef[SABSupervisor.Command]])
            )
          Behaviors.same
        }
      }.narrow[Command]

  def apply[T, R](
      config: SABConfig,
      failedResponse: => Try[R],
      isFailed: R => Boolean,
      eventHandler: Option[(ID, SABStatus) => Unit] = None
  ): Behavior[Command] =
    apply(SABSupervisor[T, R](config, failedResponse, isFailed, eventHandler))

}
