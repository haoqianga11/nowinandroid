/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.core.data.repository

import com.google.samples.apps.nowinandroid.core.data.Synchronizer
import com.google.samples.apps.nowinandroid.core.data.changeListSync
import com.google.samples.apps.nowinandroid.core.data.model.asEntity
import com.google.samples.apps.nowinandroid.core.data.model.topicCrossReferences
import com.google.samples.apps.nowinandroid.core.data.model.topicEntityShells
import com.google.samples.apps.nowinandroid.core.database.dao.NewsResourceDao
import com.google.samples.apps.nowinandroid.core.database.dao.TopicDao
import com.google.samples.apps.nowinandroid.core.database.model.PopulatedNewsResource
import com.google.samples.apps.nowinandroid.core.database.model.TopicEntity
import com.google.samples.apps.nowinandroid.core.database.model.asExternalModel
import com.google.samples.apps.nowinandroid.core.datastore.ChangeListVersions
import com.google.samples.apps.nowinandroid.core.datastore.NiaPreferencesDataSource
import com.google.samples.apps.nowinandroid.core.model.data.NewsResource
import com.google.samples.apps.nowinandroid.core.network.NiaNetworkDataSource
import com.google.samples.apps.nowinandroid.core.network.model.NetworkNewsResource
import com.google.samples.apps.nowinandroid.core.notifications.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// 每批同步新闻资源的数量，用于优化客户端和服务器之间的序列化和反序列化成本
private const val SYNC_BATCH_SIZE = 40

/**
 * NewsRepository 的离线优先实现
 * 采用本地存储作为主要数据源，支持离线访问
 * 实现了以下最佳实践：
 * 1. 离线优先策略：所有读取操作都从本地数据库进行，确保离线可用性
 * 2. 数据同步机制：通过 changeListSync 实现增量同步
 * 3. 批量处理：使用 SYNC_BATCH_SIZE 分批同步数据，优化性能
 * 4. 通知机制：集成推送通知，提醒用户新内容
 */
internal class OfflineFirstNewsRepository @Inject constructor(
    private val niaPreferencesDataSource: NiaPreferencesDataSource,  // 用户偏好数据源
    private val newsResourceDao: NewsResourceDao,                    // 新闻资源数据访问对象
    private val topicDao: TopicDao,                                 // 话题数据访问对象
    private val network: NiaNetworkDataSource,                      // 网络数据源
    private val notifier: Notifier,                                 // 通知管理器
) : NewsRepository {

    /**
     * 获取新闻资源的 Flow
     * @param query 查询条件，包含话题和新闻 ID 过滤器
     * @return 返回符合查询条件的新闻资源列表的 Flow
     */
    override fun getNewsResources(
        query: NewsResourceQuery,
    ): Flow<List<NewsResource>> = newsResourceDao.getNewsResources(
        useFilterTopicIds = query.filterTopicIds != null,
        filterTopicIds = query.filterTopicIds ?: emptySet(),
        useFilterNewsIds = query.filterNewsIds != null,
        filterNewsIds = query.filterNewsIds ?: emptySet(),
    )
        .map { it.map(PopulatedNewsResource::asExternalModel) }

    /**
     * 与远程数据源同步
     * 实现了增量同步机制，只同步发生变化的数据
     * @param synchronizer 同步器
     * @return 同步是否成功
     */
    override suspend fun syncWith(synchronizer: Synchronizer): Boolean {
        var isFirstSync = false
        return synchronizer.changeListSync(
            // 读取当前版本号
            versionReader = ChangeListVersions::newsResourceVersion,
            // 获取变更列表
            changeListFetcher = { currentVersion ->
                isFirstSync = currentVersion <= 0
                network.getNewsResourceChangeList(after = currentVersion)
            },
            // 更新版本号
            versionUpdater = { latestVersion ->
                copy(newsResourceVersion = latestVersion)
            },
            // 删除过期数据
            modelDeleter = newsResourceDao::deleteNewsResources,
            // 更新模型数据
            modelUpdater = { changedIds ->
                // 获取用户数据和偏好设置
                val userData = niaPreferencesDataSource.userData.first()
                val hasOnboarded = userData.shouldHideOnboarding
                val followedTopicIds = userData.followedTopics

                // 获取已存在且发生变化的新闻资源 ID
                val existingNewsResourceIdsThatHaveChanged = when {
                    hasOnboarded -> newsResourceDao.getNewsResourceIds(
                        useFilterTopicIds = true,
                        filterTopicIds = followedTopicIds,
                        useFilterNewsIds = true,
                        filterNewsIds = changedIds.toSet(),
                    )
                        .first()
                        .toSet()
                    else -> emptySet()
                }

                // 首次同步时，将所有新闻标记为已读
                if (isFirstSync) {
                    niaPreferencesDataSource.setNewsResourcesViewed(changedIds, true)
                }

                // 分批获取并更新变化的新闻资源
                changedIds.chunked(SYNC_BATCH_SIZE).forEach { chunkedIds ->
                    val networkNewsResources = network.getNewsResources(ids = chunkedIds)

                    // 按顺序执行以满足 ID 和外键约束
                    // 1. 插入话题
                    topicDao.insertOrIgnoreTopics(
                        topicEntities = networkNewsResources
                            .map(NetworkNewsResource::topicEntityShells)
                            .flatten()
                            .distinctBy(TopicEntity::id),
                    )
                    // 2. 更新新闻资源
                    newsResourceDao.upsertNewsResources(
                        newsResourceEntities = networkNewsResources.map(
                            NetworkNewsResource::asEntity,
                        ),
                    )
                    // 3. 更新话题交叉引用
                    newsResourceDao.insertOrIgnoreTopicCrossRefEntities(
                        newsResourceTopicCrossReferences = networkNewsResources
                            .map(NetworkNewsResource::topicCrossReferences)
                            .distinct()
                            .flatten(),
                    )
                }

                // 如果用户已完成引导，为新增的新闻资源发送通知
                if (hasOnboarded) {
                    val addedNewsResources = newsResourceDao.getNewsResources(
                        useFilterTopicIds = true,
                        filterTopicIds = followedTopicIds,
                        useFilterNewsIds = true,
                        filterNewsIds = changedIds.toSet() - existingNewsResourceIdsThatHaveChanged,
                    )
                        .first()
                        .map(PopulatedNewsResource::asExternalModel)

                    if (addedNewsResources.isNotEmpty()) {
                        notifier.postNewsNotifications(
                            newsResources = addedNewsResources,
                        )
                    }
                }
            },
        )
    }
}
