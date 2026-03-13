package me.chosante.events

import me.chosante.eventbus.Event
import java.net.URI

class BrowseEvent(
    val uri: URI,
) : Event() {
    companion object {
        fun fire(url: String) {
            publish(BrowseEvent(URI.create(url)))
        }
    }

    override fun toString() = "BrowseEvent(uri=$uri)"
}
