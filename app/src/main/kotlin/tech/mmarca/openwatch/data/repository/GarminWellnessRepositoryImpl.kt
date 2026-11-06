package tech.mmarca.openwatch.data.repository

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.data.local.garmin.GarminWellnessDao
import tech.mmarca.openwatch.data.local.garmin.GarminWellnessSampleEntity
import tech.mmarca.openwatch.data.repository.contract.GarminWellnessRepository
import tech.mmarca.openwatch.domain.model.GarminWellnessMetric
import tech.mmarca.openwatch.domain.model.GarminWellnessSample

@Singleton
class GarminWellnessRepositoryImpl @Inject constructor(
    private val dao: GarminWellnessDao,
) : GarminWellnessRepository {

    override suspend fun upsert(samples: List<GarminWellnessSample>) {
        if (samples.isEmpty()) return
        dao.upsertSamples(
            samples.map { sample ->
                GarminWellnessSampleEntity(
                    metric = sample.metric.storageName,
                    timeMillis = sample.time.toEpochMilli(),
                    value = sample.value,
                )
            },
        )
    }

    override suspend fun samplesBetween(
        metric: GarminWellnessMetric,
        from: Instant,
        to: Instant,
    ): List<GarminWellnessSample> =
        dao.samplesBetween(metric.storageName, from.toEpochMilli(), to.toEpochMilli())
            .map { it.toSample(metric) }

    override suspend fun latest(metric: GarminWellnessMetric): GarminWellnessSample? =
        dao.latest(metric.storageName)?.toSample(metric)

    override suspend fun countFor(metric: GarminWellnessMetric): Long =
        dao.countFor(metric.storageName)

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    private fun GarminWellnessSampleEntity.toSample(metric: GarminWellnessMetric): GarminWellnessSample =
        GarminWellnessSample(
            metric = metric,
            time = Instant.ofEpochMilli(timeMillis),
            value = value,
        )
}
