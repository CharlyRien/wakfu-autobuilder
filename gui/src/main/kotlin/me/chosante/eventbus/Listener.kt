package me.chosante.eventbus

/**
 * Simple annotation to indicate which methods are a listener of the eventBus
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class Listener
