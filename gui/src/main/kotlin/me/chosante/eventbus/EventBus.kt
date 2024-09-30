package me.chosante.eventbus

fun interface Subscriber<in E : Event> : (E) -> Unit

interface EventBus {
    fun <E : Event> publish(event: E)
}
