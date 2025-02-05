/*
 * Copyright 2023 The Android Open Source Project
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

package com.google.samples.apps.nowinandroid.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.core.analytics.AnalyticsEvent
import com.google.samples.apps.nowinandroid.core.analytics.AnalyticsEvent.Param
import com.google.samples.apps.nowinandroid.core.analytics.AnalyticsHelper
import com.google.samples.apps.nowinandroid.core.data.repository.RecentSearchRepository
import com.google.samples.apps.nowinandroid.core.data.repository.SearchContentsRepository
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import com.google.samples.apps.nowinandroid.core.domain.GetRecentSearchQueriesUseCase
import com.google.samples.apps.nowinandroid.core.domain.GetSearchContentsUseCase
import com.google.samples.apps.nowinandroid.core.model.data.UserSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 搜索功能的 ViewModel
 * 负责管理搜索相关的 UI 状态和业务逻辑
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    // 用于执行搜索内容的用例
    getSearchContentsUseCase: GetSearchContentsUseCase,
    // 用于获取最近搜索记录的用例
    recentSearchQueriesUseCase: GetRecentSearchQueriesUseCase,
    // 搜索内容仓库
    private val searchContentsRepository: SearchContentsRepository,
    // 最近搜索记录仓库
    private val recentSearchRepository: RecentSearchRepository,
    // 用户数据仓库
    private val userDataRepository: UserDataRepository,
    // 用于保存 ViewModel 状态
    private val savedStateHandle: SavedStateHandle,
    // 用于记录分析事件
    private val analyticsHelper: AnalyticsHelper,
) : ViewModel() {

    // 搜索查询词状态流，使用 SavedStateHandle 保存状态
    val searchQuery = savedStateHandle.getStateFlow(key = SEARCH_QUERY, initialValue = "")

    /**
     * 搜索结果的 UI 状态流
     * 包含以下状态：
     * 1. Loading：加载中
     * 2. SearchNotReady：搜索功能未就绪
     * 3. EmptyQuery：空查询
     * 4. Success：搜索成功，包含话题和新闻资源
     * 5. LoadFailed：加载失败
     */
    val searchResultUiState: StateFlow<SearchResultUiState> =
        searchContentsRepository.getSearchContentsCount()
            .flatMapLatest { totalCount ->
                // 检查搜索内容是否就绪
                if (totalCount < SEARCH_MIN_FTS_ENTITY_COUNT) {
                    flowOf(SearchResultUiState.SearchNotReady)
                } else {
                    // 监听搜索查询的变化
                    searchQuery.flatMapLatest { query ->
                        // 检查查询词长度
                        if (query.trim().length < SEARCH_QUERY_MIN_LENGTH) {
                            flowOf(SearchResultUiState.EmptyQuery)
                        } else {
                            // 执行搜索并转换结果
                            getSearchContentsUseCase(query)
                                // 不使用 asResult()，避免每次输入字符都触发 Loading 状态导致界面闪烁
                                .map<UserSearchResult, SearchResultUiState> { data ->
                                    SearchResultUiState.Success(
                                        topics = data.topics,
                                        newsResources = data.newsResources,
                                    )
                                }
                                .catch { emit(SearchResultUiState.LoadFailed) }
                        }
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchResultUiState.Loading,
            )

    /**
     * 最近搜索记录的 UI 状态流
     * 使用 UseCase 获取数据并转换为 UI 状态
     */
    val recentSearchQueriesUiState: StateFlow<RecentSearchQueriesUiState> =
        recentSearchQueriesUseCase()
            .map(RecentSearchQueriesUiState::Success)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = RecentSearchQueriesUiState.Loading,
            )

    /**
     * 处理搜索查询变化
     * 将新的查询词保存到 SavedStateHandle
     */
    fun onSearchQueryChanged(query: String) {
        savedStateHandle[SEARCH_QUERY] = query
    }

    /**
     * 处理搜索触发事件
     * 当用户点击搜索图标或按下回车键时调用
     * 保存搜索记录并记录分析事件
     */
    fun onSearchTriggered(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            recentSearchRepository.insertOrReplaceRecentSearch(searchQuery = query)
        }
        analyticsHelper.logEventSearchTriggered(query = query)
    }

    /**
     * 清除最近搜索记录
     */
    fun clearRecentSearches() {
        viewModelScope.launch {
            recentSearchRepository.clearRecentSearches()
        }
    }

    /**
     * 设置新闻资源的书签状态
     */
    fun setNewsResourceBookmarked(newsResourceId: String, isChecked: Boolean) {
        viewModelScope.launch {
            userDataRepository.setNewsResourceBookmarked(newsResourceId, isChecked)
        }
    }

    /**
     * 设置话题的关注状态
     */
    fun followTopic(followedTopicId: String, followed: Boolean) {
        viewModelScope.launch {
            userDataRepository.setTopicIdFollowed(followedTopicId, followed)
        }
    }

    /**
     * 设置新闻资源的查看状态
     */
    fun setNewsResourceViewed(newsResourceId: String, viewed: Boolean) {
        viewModelScope.launch {
            userDataRepository.setNewsResourceViewed(newsResourceId, viewed)
        }
    }
}

/**
 * 记录搜索触发事件
 */
private fun AnalyticsHelper.logEventSearchTriggered(query: String) =
    logEvent(
        event = AnalyticsEvent(
            type = SEARCH_QUERY,
            extras = listOf(element = Param(key = SEARCH_QUERY, value = query)),
        ),
    )

/** 搜索查询的最小长度，小于此长度视为空查询 */
private const val SEARCH_QUERY_MIN_LENGTH = 2

/** 搜索内容表中的最小实体数量，小于此数量视为搜索未就绪 */
private const val SEARCH_MIN_FTS_ENTITY_COUNT = 1

/** SavedStateHandle 中保存搜索查询的 key */
private const val SEARCH_QUERY = "searchQuery"
