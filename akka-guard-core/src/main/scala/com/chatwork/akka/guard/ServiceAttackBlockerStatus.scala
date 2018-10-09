package com.chatwork.akka.guard

import enumeratum._

import scala.collection.immutable

sealed abstract class ServiceAttackBlockerStatus(override val entryName: String) extends EnumEntry

object ServiceAttackBlockerStatus extends Enum[ServiceAttackBlockerStatus] {
  override def values: immutable.IndexedSeq[ServiceAttackBlockerStatus] = findValues
  case object Open   extends ServiceAttackBlockerStatus("open")
  case object Closed extends ServiceAttackBlockerStatus("close")
}
