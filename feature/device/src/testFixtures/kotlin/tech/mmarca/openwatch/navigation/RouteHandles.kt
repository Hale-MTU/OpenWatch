package tech.mmarca.openwatch.navigation

import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * The SavedStateHandle Navigation gives [route]'s destination: each property under its own name,
 * defaults included, plus what the screen [saved] of its own. For a ViewModel test. `toRoute`
 * reads it back through a Bundle, so the test runs under Robolectric.
 */
inline fun <reified T : Any> routeHandle(route: T, vararg saved: Pair<String, Any?>): SavedStateHandle {
    val encoder = RouteArgumentsEncoder()
    encoder.encodeSerializableValue(serializer<T>(), route)
    return SavedStateHandle(encoder.arguments + saved)
}

/** Writes a route's properties into a map, by name; a route holds only plain values. */
@OptIn(ExperimentalSerializationApi::class)
class RouteArgumentsEncoder : AbstractEncoder() {
    val arguments = mutableMapOf<String, Any?>()
    private var name: String? = null

    override val serializersModule: SerializersModule = EmptySerializersModule()

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        name = descriptor.getElementName(index)
        return true
    }

    override fun encodeValue(value: Any) {
        arguments[checkNotNull(name)] = value
    }

    override fun encodeNull() {
        arguments[checkNotNull(name)] = null
    }
}
