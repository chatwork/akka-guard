package com.chatwork.akka.guard.typed

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import com.chatwork.akka.guard.typed.SABActor.SABStatus
import com.chatwork.akka.guard.typed.SABSupervisor.SABSuperVisorMessage
import com.chatwork.akka.guard.typed.config.SABConfig

import scala.util.Try

object SABBroker {

  sealed trait Command
  final case class SABBrokerMessage[T, R](value: SABSuperVisorMessage[T, R]) extends Command

  def apply[T, R](
      config: SABConfig,
      failedResponse: => Try[R],
      isFailed: R => Boolean,
      eventHandler: Option[(ID, SABStatus) => Unit] = None
  ): Behavior[Command] =
    Behaviors
      .setup[AnyRef] { context =>
        Behaviors.receiveMessage {
          case SABBrokerMessage(msg) =>
            val message          = msg.asInstanceOf[SABSuperVisorMessage[T, R]]
            val commandForwarder = CommandForwarder[SABSupervisor.Command, SABSuperVisorMessage[T, R]](context)
            val behavior         = SABSupervisor[T, R](config, failedResponse, isFailed, eventHandler)
            val childName        = SABSupervisor.name(message.id)
            context
              .child(childName)
              .fold(commandForwarder.createAndForward(message, behavior, childName))(a =>
                commandForwarder.forwardMsg(message)(a.asInstanceOf[ActorRef[SABSupervisor.Command]])
              )
            Behaviors.same
        }
      }.narrow[Command]

}
