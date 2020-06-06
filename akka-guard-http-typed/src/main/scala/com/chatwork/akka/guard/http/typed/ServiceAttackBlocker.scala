package com.chatwork.akka.guard.http.typed

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, Behavior }
import akka.http.scaladsl.server.RouteResult
import com.chatwork.akka.guard.typed.SABActor.SABStatus
import com.chatwork.akka.guard.typed.config.SABConfig
import com.chatwork.akka.guard.typed.{ ID, SABBroker }

import scala.util.Try

case class ServiceAttackBlocker(
    system: ActorSystem,
    sabConfig: SABConfig,
    actorName: String = "SABBroker"
)(
    failedResponse: Try[ServiceAttackBlocker.R],
    isFailed: ServiceAttackBlocker.R => Boolean,
    eventHandler: Option[(ID, SABStatus) => Unit] = None
) {
  private lazy val behavior: Behavior[SABBroker.Command] = SABBroker(sabConfig, failedResponse, isFailed, eventHandler)
  lazy val actorRef: ActorRef[SABBroker.Command]         = system.spawn(behavior, actorName)
}

object ServiceAttackBlocker {
  type T = Unit
  type R = RouteResult
}
