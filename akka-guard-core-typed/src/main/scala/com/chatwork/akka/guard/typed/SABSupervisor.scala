package com.chatwork.akka.guard.typed

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import com.chatwork.akka.guard.typed.SABActor.{ Command, SABMessage, SABStatus }

import scala.util.Try

object SABSupervisor {

  def name(id: String): String = s"SABSupervisor-$id"

  def apply[T, R](
      id: String,
      config: SABConfig,
      failedResponse: => Try[R],
      isFailed: R => Boolean,
      eventHandler: Option[(ID, SABStatus) => Unit] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      type Message = SABMessage[T, R]
      val commandForwarder = CommandForwarder[Command, Message](context)
      val behavior         = SABActor[T, R](id, config, failedResponse, isFailed, eventHandler)
      Behaviors.receiveMessage {
        case msg: Message =>
          context
            .child(SABActor.name(msg.id))
            .fold(commandForwarder.createAndForward(msg, behavior, SABActor.name(msg.id)))(a =>
              commandForwarder.forwardMsg(msg)(a.asInstanceOf[ActorRef[Command]])
            )
          Behaviors.same
      }
    }

}
