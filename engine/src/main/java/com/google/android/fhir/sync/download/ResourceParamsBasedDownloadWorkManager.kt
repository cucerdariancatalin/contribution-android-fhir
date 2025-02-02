/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.sync.download

import com.google.android.fhir.SyncDownloadContext
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.GREATER_THAN_PREFIX
import com.google.android.fhir.sync.ParamMap
import com.google.android.fhir.sync.SyncDataParams
import com.google.android.fhir.sync.concatParams
import java.util.LinkedList
import org.hl7.fhir.exceptions.FHIRException
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType

typealias ResourceSearchParams = Map<ResourceType, ParamMap>
/**
 * [DownloadWorkManager] implementation based on the provided [ResourceSearchParams] to generate
 * [Resource] search queries and parse [Bundle.BundleType.SEARCHSET] type [Bundle]. This
 * implementation takes a DFS approach and downloads all available resources for a particular
 * [ResourceType] before moving on to the next [ResourceType].
 */
class ResourceParamsBasedDownloadWorkManager(syncParams: ResourceSearchParams) :
  DownloadWorkManager {
  private val resourcesToDownloadWithSearchParams = LinkedList(syncParams.entries)
  private val urlOfTheNextPagesToDownloadForAResource = LinkedList<String>()

  override suspend fun getNextRequestUrl(context: SyncDownloadContext): String? {
    if (urlOfTheNextPagesToDownloadForAResource.isNotEmpty())
      return urlOfTheNextPagesToDownloadForAResource.poll()

    return resourcesToDownloadWithSearchParams.poll()?.let { (resourceType, params) ->
      val newParams = params.toMutableMap()
      if (!params.containsKey(SyncDataParams.SORT_KEY)) {
        newParams[SyncDataParams.SORT_KEY] = SyncDataParams.LAST_UPDATED_KEY
      }
      if (!params.containsKey(SyncDataParams.LAST_UPDATED_KEY)) {
        val lastUpdate = context.getLatestTimestampFor(resourceType)
        if (!lastUpdate.isNullOrEmpty()) {
          newParams[SyncDataParams.LAST_UPDATED_KEY] = "$GREATER_THAN_PREFIX$lastUpdate"
        }
      }

      "${resourceType.name}?${newParams.concatParams()}"
    }
  }

  override suspend fun processResponse(response: Resource): Collection<Resource> {
    if (response is OperationOutcome) {
      throw FHIRException(response.issueFirstRep.diagnostics)
    }

    return if (response is Bundle && response.type == Bundle.BundleType.SEARCHSET) {
      response.link.firstOrNull { component -> component.relation == "next" }?.url?.let { next ->
        urlOfTheNextPagesToDownloadForAResource.add(next)
      }

      response.entry.map { it.resource }
    } else {
      emptyList()
    }
  }
}
