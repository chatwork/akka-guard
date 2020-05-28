package com.chatwork.akka.guard.typed

import enumeratum._

import scala.collection.immutable

sealed abstract class SABStatus(override val entryName: String) extends EnumEntry

object SABStatus extends Enum[SABStatus] {
  override def values: immutable.IndexedSeq[SABStatus] = findValues
  case object Open   extends SABStatus("open")
  case object Closed extends SABStatus("close")
}
