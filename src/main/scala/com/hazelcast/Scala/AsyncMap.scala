package com.hazelcast.Scala

import java.util.Map.Entry

import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import com.hazelcast.core.IMap

final class AsyncMap[K, V] private[Scala] (protected val imap: IMap[K, V])
    extends IMapAsyncDeltaUpdates[K, V] {

  def get(key: K): Future[Option[V]] = imap.getAsync(key).asScalaOpt

  def getAll(keys: Set[K])(implicit ec: ExecutionContext): Future[Map[K, V]] = {
    val fResults = keys.iterator.map { key =>
      this.get(key).map(_.map(key -> _))
    }
    Future.sequence(fResults).map(_.flatten.toMap)
  }
  def getAllAs[R](keys: Set[K], mf: V => R)(implicit ec: ExecutionContext): Future[Map[K, R]] = {
    val fResults = keys.iterator.map { key =>
      this.getAs(key, mf).map(_.map(key -> _))
    }
    Future.sequence(fResults).map(_.flatten.toMap)
  }

  def put(key: K, value: V, ttl: Duration = Duration.Zero): Future[Option[V]] =
    if (ttl.isFinite && ttl.length > 0) {
      imap.putAsync(key, value, ttl.length, ttl.unit).asScalaOpt
    } else {
      imap.putAsync(key, value).asScalaOpt
    }

  def putIfAbsent(key: K, value: V): Future[Option[V]] = {
    val ep = new SingleEntryCallbackUpdater[K, V, V] {
      def onEntry(entry: Entry[K, V]): V = {
        entry.value match {
          case null =>
            entry.value = value
            null.asInstanceOf[V]
          case value => value
        }
      }
    }
    val callback = ep.newCallbackOpt
    imap.submitToKey(key, ep, callback)
    callback.future
  }

  def remove(key: K): Future[Option[V]] =
    imap.removeAsync(key).asScalaOpt

  def getAs[R](key: K, map: V => R): Future[Option[R]] = {
    val ep = new SingleEntryCallbackReader[K, V, R] {
      def onEntry(key: K, value: V): R = {
        value match {
          case null => null.asInstanceOf[R]
          case value => map(value)
        }
      }
    }
    val callback = ep.newCallbackOpt
    imap.submitToKey(key, ep, callback)
    callback.future
  }

}
