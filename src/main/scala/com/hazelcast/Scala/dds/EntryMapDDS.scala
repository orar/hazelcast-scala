package com.hazelcast.Scala.dds

import com.hazelcast.Scala._
import java.util.Map.Entry
import com.hazelcast.query.Predicate
import com.hazelcast.query.TruePredicate

class EntryMapDDS[K, V](private val dds: MapDDS[K, V, Entry[K, V]]) extends AnyVal {
  def filterKeys(key: K, others: K*): DDS[Entry[K, V]] = filterKeys((key +: others).toSet)
  def filterKeys(f: K => Boolean): DDS[Entry[K, V]] = {
    f match {
      case set: collection.Set[K] =>
        val keySet = dds.keySet.map(_.intersect(set)).getOrElse(set.toSet)
        new MapDDS(dds.imap, dds.predicate, Some(keySet), dds.pipe)
      case filter => dds.pipe match {
        case None =>
          val predicate = new ScalaKeyPredicate[K](filter, dds.predicate.orNull.asInstanceOf[Predicate[Object, Object]])
          new MapDDS(dds.imap, Some(predicate), dds.keySet, dds.pipe)
        case Some(existingPipe) =>
          val keyFilter = (new ScalaKeyPredicate[K](filter).apply _).asInstanceOf[Entry[K, V] => Boolean]
          val pipe = new FilterPipe(keyFilter, existingPipe)
          new MapDDS(dds.imap, dds.predicate, dds.keySet, Some(pipe))
      }
    }
  }
  def filterValues(filter: V => Boolean): DDS[Entry[K, V]] = {
    dds.pipe match {
      case None =>
        val predicate = new ScalaValuePredicate[V](filter, dds.predicate.orNull.asInstanceOf[Predicate[Object, Object]])
        new MapDDS(dds.imap, Some(predicate), dds.keySet, dds.pipe)
      case Some(existingPipe) =>
        val valueFilter = (new ScalaValuePredicate[V](filter).apply _).asInstanceOf[Entry[K, V] => Boolean]
        val pipe = new FilterPipe(valueFilter, existingPipe)
        new MapDDS(dds.imap, dds.predicate, dds.keySet, Some(pipe))
    }
  }
  def mapValues[T](mvf: V => T): DDS[Entry[K, T]] = {
    val prevPipe = dds.pipe getOrElse PassThroughPipe[Entry[K, V]]
    val pipe = new MapTransformPipe(prevPipe, mvf)
    new MapDDS(dds.imap, dds.predicate, dds.keySet, Some(pipe))
  }
  def transform[T](tf: Entry[K, V] => T): DDS[Entry[K, T]] = {
    val prevPipe = dds.pipe getOrElse PassThroughPipe[Entry[K, V]]
    val pipe = new MapTransformPipe[K, V, T](tf, prevPipe)
    new MapDDS(dds.imap, dds.predicate, dds.keySet, Some(pipe))
  }

  def onKeyEvents(localOnly: Boolean = false)(pf: PartialFunction[KeyEvent[K], Unit]): ListenerRegistration =
    subscribeEntries(new KeyListener(pf), localOnly, includeValue = false)
  def onEntryEvents(localOnly: Boolean = false)(pf: PartialFunction[EntryEvent[K, V], Unit]): ListenerRegistration =
    subscribeEntries(new EntryListener(pf), localOnly, includeValue = true)

  private def subscribeEntries(
    listener: com.hazelcast.map.listener.MapListener,
    localOnly: Boolean,
    includeValue: Boolean): ListenerRegistration = {

    val (singleKey, predicate) = dds.keySet match {
      case None =>
        None -> dds.predicate.getOrElse(TruePredicate.INSTANCE).asInstanceOf[Predicate[K, V]]
      case Some(keys) if keys.size <= 1 =>
        keys.headOption -> dds.predicate.getOrElse(TruePredicate.INSTANCE).asInstanceOf[Predicate[K, V]]
      case Some(keys) =>
        None -> (dds.predicate match {
          case None => new ScalaKeyPredicate(keys).asInstanceOf[Predicate[K, V]]
          case Some(predicate) => (new ScalaKeyPredicate(keys) && predicate).asInstanceOf[Predicate[K, V]]
        })
    }
    val regId = singleKey match {
      case Some(key) if localOnly => dds.imap.addLocalEntryListener(listener, predicate, key, includeValue)
      case None if localOnly => dds.imap.addLocalEntryListener(listener, predicate, includeValue)
      case Some(key) => dds.imap.addEntryListener(listener, predicate, key, includeValue)
      case None => dds.imap.addEntryListener(listener, predicate, includeValue)
    }
    new ListenerRegistration {
      def cancel(): Unit = dds.imap.removeEntryListener(regId)
    }
  }

}
