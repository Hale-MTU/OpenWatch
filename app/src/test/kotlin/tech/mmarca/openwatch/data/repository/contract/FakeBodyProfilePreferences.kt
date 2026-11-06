package tech.mmarca.openwatch.data.repository.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tech.mmarca.openwatch.domain.preferences.BodyProfile

class FakeBodyProfilePreferences(initialProfile: BodyProfile = BodyProfile()) : BodyProfilePreferences {
    private val profile = MutableStateFlow(initialProfile.normalized())

    override fun bodyProfile(): BodyProfile = profile.value

    override val bodyProfileFlow: Flow<BodyProfile> = profile

    override fun setBodyProfile(profile: BodyProfile) {
        this.profile.value = profile.normalized()
    }
}
