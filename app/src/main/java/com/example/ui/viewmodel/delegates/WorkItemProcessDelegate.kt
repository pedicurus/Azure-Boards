package com.example.ui.viewmodel.delegates

import com.example.data.repository.AzureDevOpsRepository
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.expandAndSortHierarchyPaths
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProjectAreas
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.findProjectIterations
import com.example.ui.viewmodel.WorkItemsFilterAndHierarchyHelper.projectScopedKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Delegate managing cached project process hierarchies, category maps, backlog levels,
 * and Area/Iteration classification trees.
 */
class WorkItemProcessDelegate(
    private val repository: AzureDevOpsRepository
) {
    private val _projectProcessMap = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    val projectProcessMap: StateFlow<Map<String, Map<String, List<String>>>> = _projectProcessMap.asStateFlow()

    private val _projectCategoryMap = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val projectCategoryMap: StateFlow<Map<String, Map<String, String>>> = _projectCategoryMap.asStateFlow()

    private val _projectBacklogLevelsMap = MutableStateFlow<Map<String, List<List<String>>>>(emptyMap())
    val projectBacklogLevelsMap: StateFlow<Map<String, List<List<String>>>> = _projectBacklogLevelsMap.asStateFlow()

    private val _projectAreasMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val projectAreasMap: StateFlow<Map<String, List<String>>> = _projectAreasMap.asStateFlow()

    private val _projectIterationsMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val projectIterationsMap: StateFlow<Map<String, List<String>>> = _projectIterationsMap.asStateFlow()

    /** Fetches currently running, by lower-case "org|project" key; later callers wait for them. */
    private val inFlightFetches = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** When each project's metadata was last fetched successfully (epoch ms), by the same key. */
    private val fetchedAtByProject = ConcurrentHashMap<String, Long>()

    /** Restores fetch times saved with the offline metadata cache, so a cached start can skip them. */
    fun restoreFetchedAt(savedFetchedAt: Map<String, Long>) {
        savedFetchedAt.forEach { (key, fetchedAt) ->
            fetchedAtByProject.merge(key.lowercase(), fetchedAt) { old, new -> maxOf(old, new) }
        }
    }

    private fun isFresh(key: String): Boolean {
        val fetchedAt = fetchedAtByProject[key] ?: return false
        return System.currentTimeMillis() - fetchedAt < METADATA_FRESHNESS_MS
    }

    fun updateMaps(
        processMap: Map<String, Map<String, List<String>>>,
        categoryMap: Map<String, Map<String, String>>,
        backlogLevelsMap: Map<String, List<List<String>>>,
        areasMap: Map<String, List<String>>,
        iterationsMap: Map<String, List<String>>
    ) {
        _projectProcessMap.value = processMap
        _projectCategoryMap.value = categoryMap
        _projectBacklogLevelsMap.value = backlogLevelsMap
        _projectAreasMap.value = areasMap
        _projectIterationsMap.value = iterationsMap
    }

    /**
     * Loads a project's process, backlog and area/iteration metadata (about 13 + 2 per work item
     * type requests) and stores it in memory and in the offline cache.
     *
     * - Concurrent calls for the same project share one fetch.
     * - Metadata fetched less than [METADATA_FRESHNESS_MS] ago (in this process or restored from
     *   the offline cache) is reused; pass [force] = true to refetch anyway (pull-to-refresh).
     */
    suspend fun fetchAndStoreProjectMetadata(
        normOrg: String,
        project: String,
        pat: String,
        force: Boolean = false
    ) {
        val cleanProject = project.trim()
        if (normOrg.isBlank() || cleanProject.isBlank() || pat.isBlank()) return
        val key = projectScopedKey(normOrg, cleanProject).lowercase()
        if (!force && isFresh(key)) return

        val ownFetch = CompletableDeferred<Unit>()
        val runningFetch = inFlightFetches.putIfAbsent(key, ownFetch)
        if (runningFetch != null) {
            // Always completed normally (see finally below), so awaiting never throws.
            runningFetch.await()
            return
        }
        try {
            fetchAndStoreProjectMetadataNow(normOrg, cleanProject, pat, key)
        } finally {
            inFlightFetches.remove(key, ownFetch)
            ownFetch.complete(Unit)
        }
    }

    private suspend fun fetchAndStoreProjectMetadataNow(
        normOrg: String,
        cleanProject: String,
        pat: String,
        freshnessKey: String
    ) = withContext(Dispatchers.IO) {
        val scopedKey = projectScopedKey(normOrg, cleanProject)

        coroutineScope {
            val processDeferred = async { repository.fetchProjectProcessHierarchy(normOrg, cleanProject, pat) }
            val classificationDeferred = async { repository.fetchProjectClassificationPaths(normOrg, cleanProject, pat) }

            val processHierarchy = processDeferred.await()
            val activeTypes = processHierarchy.keys
                .filterNot { AzureDevOpsRepository.isExcludedNonWorkItemType(it) }
                .toList()
            val backlogMeta = repository.fetchProjectBacklogMetadata(
                organization = normOrg,
                project = cleanProject,
                pat = pat,
                activeTypesHint = activeTypes
            )
            val (areas, iterations) = classificationDeferred.await()

            val categoryMap = backlogMeta.categoryMap
            val backlogLevels = backlogMeta.orderedBacklogLevels

            if (processHierarchy.isNotEmpty()) {
                val cleanedMap = _projectProcessMap.value.filterKeys { k ->
                    !k.equals(scopedKey, ignoreCase = true) && !k.equals(cleanProject, ignoreCase = true)
                }
                _projectProcessMap.value = cleanedMap + (scopedKey to processHierarchy)
            }
            if (categoryMap.isNotEmpty()) {
                val cleanedCat = _projectCategoryMap.value.filterKeys { k ->
                    !k.equals(scopedKey, ignoreCase = true) && !k.equals(cleanProject, ignoreCase = true)
                }
                _projectCategoryMap.value = cleanedCat + (scopedKey to categoryMap)
            }
            if (backlogLevels.isNotEmpty()) {
                val cleanedLevels = _projectBacklogLevelsMap.value.filterKeys { k ->
                    !k.equals(scopedKey, ignoreCase = true) && !k.equals(cleanProject, ignoreCase = true)
                }
                _projectBacklogLevelsMap.value = cleanedLevels + (scopedKey to backlogLevels)
            }
            if (areas.isNotEmpty()) {
                _projectAreasMap.value = _projectAreasMap.value + (scopedKey to areas)
            }
            if (iterations.isNotEmpty()) {
                _projectIterationsMap.value = _projectIterationsMap.value + (scopedKey to iterations)
            }
            // Repositories return empty results on network errors: only a real result is "fresh".
            if (processHierarchy.isNotEmpty()) {
                fetchedAtByProject[freshnessKey] = System.currentTimeMillis()
            }

            repository.saveCachedOrganizationMetadata(
                organization = normOrg,
                processHierarchyByProject = _projectProcessMap.value,
                categoryMapByProject = _projectCategoryMap.value,
                backlogLevelsByProject = _projectBacklogLevelsMap.value,
                areasByProject = _projectAreasMap.value,
                iterationsByProject = _projectIterationsMap.value,
                fetchedAtByProject = fetchedAtByProject.toMap()
            )
        }
    }

    fun getAreasForProject(activeOrg: String?, project: String?): List<String> {
        val clean = project?.trim().orEmpty()
        if (clean.isEmpty()) return emptyList()
        val found = findProjectAreas(activeOrg, clean, _projectAreasMap.value)
        return if (!found.isNullOrEmpty()) {
            expandAndSortHierarchyPaths(clean, found)
        } else {
            emptyList()
        }
    }

    fun getIterationsForProject(activeOrg: String?, project: String?): List<String> {
        val clean = project?.trim().orEmpty()
        if (clean.isEmpty()) return emptyList()
        val found = findProjectIterations(activeOrg, clean, _projectIterationsMap.value)
        return if (!found.isNullOrEmpty()) {
            expandAndSortHierarchyPaths(clean, found)
        } else {
            emptyList()
        }
    }

    fun clearAll() {
        fetchedAtByProject.clear()
        _projectProcessMap.value = emptyMap()
        _projectCategoryMap.value = emptyMap()
        _projectBacklogLevelsMap.value = emptyMap()
        _projectAreasMap.value = emptyMap()
        _projectIterationsMap.value = emptyMap()
    }

    companion object {
        /** Process settings rarely change; pull-to-refresh bypasses this. */
        const val METADATA_FRESHNESS_MS = 24L * 60L * 60L * 1000L
    }
}
