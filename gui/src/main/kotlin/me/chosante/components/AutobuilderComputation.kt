package me.chosante.components

import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.eventbus.DefaultEventBus
import me.chosante.eventbus.Event.Companion.publish
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildCancelSearchEvent
import me.chosante.events.AutobuildEndSearchEvent
import me.chosante.events.AutobuildStartSearchEvent
import me.chosante.events.AutobuildUpdateSearchEvent

@ExperimentalCoroutinesApi
@ExperimentalTime
class AutobuilderComputation : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    private var job: Job? = null

    init {
        DefaultEventBus.subscribe(AutobuildCancelSearchEvent::class, ::cancel)
    }
    fun start(wakfuBestBuildParams: WakfuBestBuildParams) {
        job = launch {
            WakfuBestBuildFinderAlgorithm.run(wakfuBestBuildParams)
                .buffer(capacity = CONFLATED)
                .onEach {
                    publish(AutobuildUpdateSearchEvent(it))
                    delay(1000L)
                }
                .onCompletion { publish(AutobuildEndSearchEvent()) }
                .onStart { publish(AutobuildStartSearchEvent()) }
                .collect()
        }
    }

    @Listener
    fun cancel(event: AutobuildCancelSearchEvent) {
        job?.cancel()
    }
}
