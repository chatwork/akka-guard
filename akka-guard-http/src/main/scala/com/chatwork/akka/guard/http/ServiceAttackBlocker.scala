package com.chatwork.akka.guard.http
import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.http.scaladsl.server.RouteResult
import com.chatwork.akka.guard.{ ID, SABBroker, SABConfig, SABStatus }

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
  import ServiceAttackBlocker._
  private lazy val broker: SABBroker[T, R] = new SABBroker(sabConfig, failedResponse, isFailed, eventHandler)
  private lazy val props: Props            = Props(broker)
  lazy val actorRef: ActorRef              = system.actorOf(props, actorName)
}

object ServiceAttackBlocker {
  type T = Unit
  type R = RouteResult
}
