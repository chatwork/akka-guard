package com.chatwork.akka.guard

import enumeratum._

import scala.collection.immutable

sealed abstract class BFABlockerStatus(override val entryName: String) extends EnumEntry

object BFABlockerStatus extends Enum[BFABlockerStatus] {
  override def values: immutable.IndexedSeq[BFABlockerStatus] = findValues
  case object Open   extends BFABlockerStatus("open")
  case object Closed extends BFABlockerStatus("close")
}
