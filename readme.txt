# Now in Android 项目架构设计与数据流向总结

## 一、整体架构层次

### 1. 表现层（Presentation Layer）- MVVM模式
#### 1.1 View（Compose UI）
- 职责：UI展示和用户交互
- 实现：
  - 界面组件（如 SearchScreen）
  - 状态收集和展示
  - 用户事件处理
- 特点：
  - 使用 Jetpack Compose 声明式UI
  - 通过 collectAsStateWithLifecycle 观察状态
  - 完全无状态，所有状态由 ViewModel 管理

#### 1.2 ViewModel
- 职责：状态管理和业务逻辑处理
- 实现：
  - 状态管理（如 SearchViewModel）
  - 用户事件处理
  - 与领域层交互
- 特点：
  - 使用 StateFlow 管理UI状态
  - 通过依赖注入获取用例
  - 处理生命周期相关的数据存储

#### 1.3 Model
- 职责：UI状态和数据模型定义
- 实现：
  - UI状态类（如 SearchResultUiState）
  - 界面数据模型
- 特点：
  - 密封类定义状态
  - 不可变数据类
  - 清晰的状态转换

### 2. 领域层（Domain Layer）
#### 2.1 Use Cases
- 职责：业务规则和流程
- 实现：
  - 独立用例类（如 GetSearchContentsUseCase）
  - 数据转换和业务规则
- 特点：
  - 单一职责原则
  - 可复用的业务逻辑
  - 与框架无关的纯Kotlin实现

#### 2.2 Domain Models
- 职责：核心业务模型
- 实现：
  - 领域实体（如 NewsResource）
  - 业务规则定义
- 特点：
  - 框架无关的纯数据类
  - 包含业务规则验证
  - 领域驱动设计思想

### 3. 数据层（Data Layer）
#### 3.1 Repositories
- 职责：数据操作的抽象
- 实现：
  - 仓库接口（如 SearchContentsRepository）
  - 具体实现（如 OfflineFirstSearchRepository）
- 特点：
  - 单一数据来源原则
  - 数据同步策略
  - 缓存机制

#### 3.2 Data Sources
- 职责：具体数据操作
- 实现：
  - 本地数据源（Room Database）
  - 远程数据源（Retrofit API）
  - 偏好设置（DataStore）
- 特点：
  - 数据持久化
  - 网络请求处理
  - 数据格式转换

## 二、数据流向与交互

### 1. 向下数据流
1. 用户操作触发 View 层事件
2. ViewModel 接收事件并调用相应用例
3. Use Case 执行业务逻辑
4. Repository 协调数据源操作
5. Data Source 执行具体的数据操作

### 2. 向上数据流
1. Data Source 返回原始数据
2. Repository 转换和组合数据
3. Use Case 应用业务规则
4. ViewModel 转换为 UI 状态
5. View 展示最终的 UI

### 3. 依赖规则
- 外层依赖内层（表现层 → 领域层 → 数据层）
- 内层不知道外层的存在
- 通过接口实现依赖反转
- 使用 Hilt 进行依赖注入

## 三、关键实现机制

### 1. 状态管理
```kotlin
// ViewModel 中的状态定义
val searchResultUiState: StateFlow<SearchResultUiState> =
    searchContentsRepository.getSearchContentsCount()
        .flatMapLatest { ... }
        .stateIn(viewModelScope)
```

### 2. 依赖注入
```kotlin
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val getSearchContentsUseCase: GetSearchContentsUseCase,
    private val searchContentsRepository: SearchContentsRepository,
) : ViewModel()
```

### 3. 数据转换
```kotlin
// Use Case 中的数据转换
class GetSearchContentsUseCase @Inject constructor(
    private val searchContentsRepository: SearchContentsRepository,
) {
    operator fun invoke(query: String): Flow<UserSearchResult> =
        searchContentsRepository.getSearchContents(query)
            .map { it.toUserSearchResult() }
}
```

## 四、最佳实践

### 1. 架构原则
- 单一职责原则
- 依赖倒置原则
- 接口隔离原则
- 关注点分离

### 2. 测试策略
- 表现层：UI 测试和 ViewModel 单元测试
- 领域层：用例的单元测试
- 数据层：Repository 和 DataSource 的单元测试

### 3. 性能优化
- 响应式数据流
- 协程和 Flow 的合理使用
- 缓存策略
- 批量处理

### 4. 可维护性
- 模块化结构
- 清晰的职责划分
- 统一的命名规范
- 完善的文档注释 