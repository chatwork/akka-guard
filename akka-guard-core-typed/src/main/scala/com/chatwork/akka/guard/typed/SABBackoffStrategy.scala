package com.chatwork.akka.guard.typed

import enumeratum._

sealed abstract class SABBackoffStrategy(override val entryName: String) extends EnumEntry

object SABBackoffStrategy extends Enum[SABBackoffStrategy] {
  override def values: IndexedSeq[SABBackoffStrategy] = findValues

  case object Lineal      extends SABBackoffStrategy("lineal")
  case object Exponential extends SABBackoffStrategy("exponential")
}
