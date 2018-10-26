package com.chatwork.akka.guard

import scala.concurrent.Future

case class SABMessage[T, R](id: String, request: T, handler: T => Future[R]) {
  def execute: Future[R] = handler(request)
}
