package com.chatwork.akka.guard

import akka.actor.{ Actor, ActorRef, Props }

trait MessageForwarder { this: Actor =>
  type Message

  protected def forwardMsg(msg: Message)(childRef: ActorRef): Unit =
    childRef forward msg

  protected def createActor(id: String, props: Props, name: String): ActorRef =
    context.actorOf(props, name)

  protected def createAndForward(msg: Message, id: String, props: Props, name: String): Unit =
    forwardMsg(msg)(createActor(id, props, name))
}
