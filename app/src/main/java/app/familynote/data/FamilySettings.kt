package app.familynote.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who mainly drives this car. Notes addressed to that person (or to the whole family) are
 * the ones that greet the driver on arrival; notes for others wait for them.
 */
class FamilySettings(
    initialDriver: String,
    private val persist: (String) -> Unit = {},
) {
    private val _driver = MutableStateFlow(initialDriver)
    val driver: StateFlow<String> = _driver.asStateFlow()

    fun setDriver(name: String) {
        _driver.value = name
        persist(name)
    }

    fun isForDriver(note: Note): Boolean = isFor(note, _driver.value)

    companion object {
        const val WHOLE_FAMILY = "우리 가족"
        fun isFor(note: Note, driver: String) = note.toMember == driver || note.toMember == WHOLE_FAMILY
    }
}
