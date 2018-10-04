package com.chatwork.akka.guard.http

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ Directive, Directive0, RouteResult }
import akka.pattern.ask
import akka.util.Timeout
import com.chatwork.akka.guard._

import scala.concurrent.Future

trait BFABlockerDirectives {

  type T = Unit
  type R = RouteResult

  protected val bfaActorSystem: ActorSystem
  protected val bfaConfig: BFABrokerConfig[T, R]

  private lazy val bfaBroker: BFABroker[T, R] = new BFABroker(bfaConfig)
  private lazy val props                      = Props(bfaBroker)
  private lazy val bfaBrokerRef: ActorRef     = bfaActorSystem.actorOf(props)
  implicit val timeout: Timeout

  def bfaBlocker(id: String): Directive0 =
    extractExecutionContext.flatMap { implicit ex =>
      Directive[T] { inner => ctx =>
        val message: BFAMessage[T, R] = BFAMessage(id, (), a => inner(a)(ctx))
        for {
          a <- (bfaBrokerRef ? message).mapTo[Future[R]]
          b <- a
        } yield b
      }
    }

}
