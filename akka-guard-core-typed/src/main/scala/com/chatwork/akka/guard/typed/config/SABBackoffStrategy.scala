package com.chatwork.akka.guard.typed.config

import enumeratum._

import scala.collection.immutable

sealed abstract class SABBackoffStrategy(override val entryName: String) extends EnumEntry

object SABBackoffStrategy extends Enum[SABBackoffStrategy] {
  override def values: immutable.IndexedSeq[SABBackoffStrategy] = findValues

  case object Lineal      extends SABBackoffStrategy("lineal")
  case object Exponential extends SABBackoffStrategy("exponential")
}
