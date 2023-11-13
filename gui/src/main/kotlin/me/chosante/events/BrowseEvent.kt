package me.chosante.events

import java.net.URI
import me.chosante.eventbus.Event

class BrowseEvent(val uri: URI) : Event() {
    companion object {
        fun fire(url: String) {
            publish(BrowseEvent(URI.create(url)))
        }
    }

    override fun toString() = "BrowseEvent(uri=$uri)"
}
