package me.chosante.eventbus

import java.util.UUID

abstract class Event {
    val id: UUID = UUID.randomUUID()

    override fun toString(): String {
        return "Event(id=$id)"
    }

    companion object {
        fun <E : Event> publish(event: E) {
            DefaultEventBus.publish(event)
        }
    }
}
