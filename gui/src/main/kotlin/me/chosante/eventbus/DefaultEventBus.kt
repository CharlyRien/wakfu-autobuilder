package me.chosante.eventbus

import kotlin.reflect.KClass

/**
 * Simple event bus implementation.
 *
 *
 * Subscribe and publish events. Events are published in channels distinguished by event type.
 * Channels can be grouped using an event type hierarchy.
 */
object DefaultEventBus : EventBus {
    private val subscribers = mutableMapOf<KClass<*>, List<Subscriber<*>>>()

    fun <T : Event> subscribe(type: KClass<T>, subscriber: Subscriber<T>) {
        val existing = subscribers.getOrDefault(type, emptyList())
        subscribers[type] = existing + subscriber
    }

    @Suppress("UNCHECKED_CAST")
    override fun <E : Event> publish(event: E) {
        subscribers.filter { it.key.isInstance(event) }
            .flatMap { it.value }
            .forEach { subscriber -> (subscriber as Subscriber<E>).invoke(event) }
    }
}
