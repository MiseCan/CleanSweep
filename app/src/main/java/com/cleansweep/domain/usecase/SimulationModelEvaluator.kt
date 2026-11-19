package com.cleansweep.domain.usecase

import android.content.Context
import android.os.SystemClock
import com.cleansweep.data.repository.PhotoFeatureCache
import com.cleansweep.diagnostics.ErrorJournal
import com.cleansweep.domain.model.ModelTiming
import com.cleansweep.domain.model.SimulationModel
import com.cleansweep.domain.model.SimulationWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SimulationModelEvaluator(private val context: Context) {
    suspend fun evaluate(
        model: SimulationModel,
        windows: List<SimulationWindow>
    ): Pair<List<SimulationWindow>, ModelTiming> {
        val start = SystemClock.elapsedRealtime()
        if (windows.none { window -> window.comparisons.any { !it.similar } }) {
            return windows to ModelTiming(model, 0, 0, 0, 0)
        }
        var scored = 0
        var encoded = 0
        var cached = 0
        val results = try {
            PhotoFeatureCache(context).use { cache ->
                ImageEmbeddingSession(context, cache, model).use { embeddings ->
                    val evaluated = windows.map { window ->
                        embeddings.clearWindow()
                        window.copy(comparisons = window.comparisons.map pairMap@ { pair ->
                            currentCoroutineContext().ensureActive()
                            if (pair.similar) return@pairMap pair
                            val a = embeddings.vector(pair.earlier) ?: return@pairMap pair
                            currentCoroutineContext().ensureActive()
                            val b = embeddings.vector(pair.later) ?: return@pairMap pair
                            scored++
                            pair.copy(modelScores = pair.modelScores + (model to embeddingSimilarity(a, b)))
                        })
                    }
                    encoded = embeddings.encoded
                    cached = embeddings.cached
                    evaluated
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ErrorJournal.warn("Simulation image encoder failed: ${model.name}", error)
            return windows to ModelTiming(model, SystemClock.elapsedRealtime() - start, 0, encoded, cached, failed = true)
        }
        return results to ModelTiming(model, SystemClock.elapsedRealtime() - start, scored, encoded, cached)
    }
}
