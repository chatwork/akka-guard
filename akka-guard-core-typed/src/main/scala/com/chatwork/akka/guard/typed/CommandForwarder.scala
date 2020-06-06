package com.chatwork.akka.guard.typed

import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.{ ActorRef, Behavior }

case class CommandForwarder[Command, Message <: Command](context: ActorContext[AnyRef]) {

  def forwardMsg(msg: Message)(childRef: ActorRef[Command]): Unit =
    childRef ! msg

  private def createActor(behavior: Behavior[Command], name: String): ActorRef[Command] =
    context.spawn(behavior, name)

  def createAndForward(msg: Message, behavior: Behavior[Command], name: String): Unit =
    forwardMsg(msg)(createActor(behavior, name))

}
