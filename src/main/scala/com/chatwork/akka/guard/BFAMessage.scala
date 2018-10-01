package com.chatwork.akka.guard

case class BFAMessage[T](id: String, request: T)
