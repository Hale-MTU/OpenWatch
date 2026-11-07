package tech.mmarca.openwatch.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.ui.components.OpenWatchButton
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The declared body: birth year, sex, height and weight. The walking energy
 * needs the weight.
 * A weight or height Health Connect holds is shown and wins over the typed one.
 */
@Composable
fun BodyProfileScreen(
    viewModel: BodyProfileViewModel,
    unitFormatter: UnitFormatter,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val imperial = state.unitSystem == UnitSystem.IMPERIAL
    var birthYear by remember(state.declared) { mutableStateOf(state.declared.birthYear?.toString().orEmpty()) }
    var sex by remember(state.declared) { mutableStateOf(state.declared.sex) }
    var height by remember(state.declared, imperial) {
        mutableStateOf(state.declared.heightCm?.let { formatLength(it, imperial) }.orEmpty())
    }
    var weight by remember(state.declared, imperial) {
        mutableStateOf(state.declared.weightKg?.let { formatWeight(it, imperial) }.orEmpty())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = stringResource(R.string.body_profile_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenWatchCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                OutlinedTextField(
                    value = birthYear,
                    onValueChange = { birthYear = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.body_profile_birth_year)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(text = stringResource(R.string.body_profile_sex), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    BiologicalSex.entries.forEach { option ->
                        FilterChip(
                            selected = sex == option,
                            onClick = { sex = if (sex == option) null else option },
                            label = { Text(stringResource(option.labelRes())) },
                        )
                    }
                }
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it },
                    label = {
                        Text(stringResource(if (imperial) R.string.body_profile_height_in else R.string.body_profile_height_cm))
                    },
                    supportingText = state.measuredHeightCm?.let { measured ->
                        { Text(stringResource(R.string.body_profile_measured, unitFormatter.height(measured).text)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = {
                        Text(stringResource(if (imperial) R.string.body_profile_weight_lb else R.string.body_profile_weight_kg))
                    },
                    supportingText = state.measuredWeightKg?.let { measured ->
                        { Text(stringResource(R.string.body_profile_measured, unitFormatter.weight(measured).text)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OpenWatchButton(
                    onClick = {
                        viewModel.save(
                            BodyProfile(
                                birthYear = birthYear.toIntOrNull(),
                                sex = sex,
                                heightCm = height.toDecimalOrNull()?.let { if (imperial) it * CmPerInch else it },
                                weightKg = weight.toDecimalOrNull()?.let { if (imperial) it * KgPerPound else it },
                                restingHeartRateBpm = state.declared.restingHeartRateBpm,
                                maxHeartRateBpm = state.declared.maxHeartRateBpm,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

private fun BiologicalSex.labelRes(): Int = when (this) {
    BiologicalSex.FEMALE -> R.string.body_profile_sex_female
    BiologicalSex.MALE -> R.string.body_profile_sex_male
}

private fun formatLength(cm: Double, imperial: Boolean): String =
    if (imperial) String.format(Locale.US, "%.1f", cm / CmPerInch) else cm.roundToInt().toString()

private fun formatWeight(kg: Double, imperial: Boolean): String =
    String.format(Locale.US, "%.1f", if (imperial) kg / KgPerPound else kg)

private fun String.toDecimalOrNull(): Double? = trim().replace(',', '.').toDoubleOrNull()

private const val CmPerInch = 2.54
private const val KgPerPound = 0.45359237
