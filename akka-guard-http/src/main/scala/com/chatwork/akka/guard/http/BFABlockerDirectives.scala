package com.chatwork.akka.guard.http

import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout

trait BFABlockerDirectives[R] {

  protected val name: String
  implicit protected val timeout: Timeout

  def bfaBlocker(id: String): Directive0 =
    extractActorSystem.flatMap { system =>
      extractExecutionContext.flatMap { implicit ex =>
        extractRequest.flatMap { request =>

          pass
        }
      }
    }

}
