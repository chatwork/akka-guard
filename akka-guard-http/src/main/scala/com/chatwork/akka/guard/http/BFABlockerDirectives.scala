package com.chatwork.akka.guard.http

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.pattern.ask
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.{ Directive, Directive0, Directive1 }
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import com.chatwork.akka.guard.{ BFABroker, BFABrokerConfig, BFAMessage }

import scala.concurrent.Future

trait BFABlockerDirectives[R] {

  protected val name: String
  protected val config: BFABrokerConfig[HttpRequest, R]
  protected val system: ActorSystem
  implicit protected val timeout: Timeout

  private lazy val bfaBroker: BFABroker[HttpRequest, R] = new BFABroker(config)
  private lazy val bfaBrokerRef: ActorRef               = system.actorOf(Props(bfaBroker), name)

  def bfaBlocker(id: String): Directive0 =
    extractExecutionContext.flatMap { implicit ex =>
      extractRequest.flatMap { request =>
        val res = for {
          a <- (bfaBrokerRef ? BFAMessage(id, request)).mapTo[Future[R]]
          r <- a
        } yield r
        pass
      }
    }

}
