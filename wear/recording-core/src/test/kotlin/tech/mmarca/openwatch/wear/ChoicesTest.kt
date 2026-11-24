package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class ChoicesTest {

    private enum class Item { STEPS, HEART_RATE, DISTANCE, FLOORS, BLOOD_OXYGEN }

    private val defaults = listOf(Item.STEPS, Item.HEART_RATE)

    private fun decode(raw: String?): List<Item> = Choices.decode(raw, defaults) { name -> Item.entries.firstOrNull { it.name == name } }

    private fun encode(items: List<Item>): String = Choices.encode(items) { it.name }

    @Test
    fun `nothing stored is the defaults, an empty string is none`() {
        assertEquals(defaults, decode(null))
        assertEquals(emptyList<Item>(), decode(""))
    }

    @Test
    fun `a choice reads back as it was stored, in its order`() {
        val custom = listOf(Item.HEART_RATE, Item.STEPS, Item.FLOORS)

        assertEquals("HEART_RATE,STEPS,FLOORS", encode(custom))
        assertEquals(custom, decode(encode(custom)))
    }

    @Test
    fun `an unknown name and a second copy are dropped`() {
        assertEquals(listOf(Item.STEPS, Item.HEART_RATE), decode("STEPS,BODY_BATTERY, HEART_RATE,STEPS"))
    }

    @Test
    fun `an item switched on goes last, once, and switched off goes`() {
        assertEquals(listOf(Item.STEPS, Item.HEART_RATE, Item.FLOORS), Choices.switched(defaults, Item.FLOORS, on = true))
        assertEquals(defaults, Choices.switched(defaults, Item.STEPS, on = true))
        assertEquals(listOf(Item.HEART_RATE), Choices.switched(defaults, Item.STEPS, on = false))
        assertEquals(defaults, Choices.switched(defaults, Item.FLOORS, on = false))
    }

    @Test
    fun `a move trades places with the neighbour, and stops at either end`() {
        val chosen = listOf(Item.STEPS, Item.HEART_RATE, Item.DISTANCE, Item.FLOORS)
        val all: (Item) -> Boolean = { true }

        assertEquals(listOf(Item.HEART_RATE, Item.STEPS, Item.DISTANCE, Item.FLOORS), Choices.moved(chosen, Item.HEART_RATE, up = true, all))
        assertEquals(listOf(Item.STEPS, Item.HEART_RATE, Item.FLOORS, Item.DISTANCE), Choices.moved(chosen, Item.DISTANCE, up = false, all))
        assertEquals(chosen, Choices.moved(chosen, Item.STEPS, up = true, all))
        assertEquals(chosen, Choices.moved(chosen, Item.FLOORS, up = false, all))
    }

    @Test
    fun `a move skips the items not shown, which keep their places`() {
        // Blood oxygen is chosen but not offered on this watch now, so the editor does not show it.
        val chosen = listOf(Item.STEPS, Item.BLOOD_OXYGEN, Item.HEART_RATE)
        val shown: (Item) -> Boolean = { it != Item.BLOOD_OXYGEN }

        val up = Choices.moved(chosen, Item.HEART_RATE, up = true, shown)

        assertEquals(listOf(Item.HEART_RATE, Item.BLOOD_OXYGEN, Item.STEPS), up)
        assertEquals(chosen, Choices.moved(up, Item.HEART_RATE, up = false, shown))
        assertEquals(chosen, Choices.moved(chosen, Item.BLOOD_OXYGEN, up = true, shown))
    }
}
