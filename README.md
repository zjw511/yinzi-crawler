# 寅子鱼吧爬虫 App

一个 Android 原生 App，自动抓取斗鱼鱼吧（默认 **寅子鱼吧，group_id=561**）帖子里的图片和视频，并保存到手机相册。

> 仅用于个人学习与收藏，请勿将抓到的内容用于商业分发或公开传播。下载的内容版权归原作者所有。

## 功能

- 拉取指定鱼吧的帖子列表（默认寅子）
- 自动解析帖子里的图片（jpg/png/webp 等）和视频（mp4 直链）
- 一键下载单帖媒体，或下载当前列表全部媒体
- 图片保存到相册 `Pictures/YinziCrawler/Images`，视频保存到 `Movies/YinziCrawler/Videos`
- 后台前台服务下载，退到后台不被杀
- group_id 可配置（默认 561，可改成任意主播鱼吧）
- Cookie 可配置（部分内容必须登录后才能抓到）

## 技术栈

- Kotlin + Coroutines
- OkHttp + Retrofit（网络）
- Jsoup + kotlinx.serialization（HTML / JSON 解析，带兜底）
- Glide（图片加载）
- Material Components + ViewBinding
- MediaStore（保存到相册，Android 10+ 无需写权限）
- 前台 Service（后台下载）

## 目录结构

```
YinziCrawler/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/yinzi/crawler/
│       │   ├── App.kt                      # Application，初始化网络/通知通道
│       │   ├── model/Models.kt             # Post / MediaItem / API 响应
│       │   ├── network/
│       │   │   ├── Net.kt                   # OkHttp + Retrofit 单例，注入 Cookie/UA
│       │   │   ├── YubaApi.kt               # 鱼吧接口定义
│       │   │   ├── YubaParser.kt            # JSON/HTML 解析（核心，容错）
│       │   │   └── YubaRepository.kt        # 高层封装：API→HTML 兜底
│       │   ├── download/
│       │   │   ├── DownloadManager.kt      # 下载 + MediaStore 保存 + 进度
│       │   │   └── DownloadService.kt       # 前台服务批量下载
│       │   ├── util/{Prefs,PermissionUtil}.kt
│       │   └── ui/
│       │       ├── MainActivity.kt
│       │       ├── MainViewModel.kt
│       │       ├── PostAdapter.kt
│       │       ├── MediaAdapter.kt
│       │       └── SettingsDialog.kt
│       └── res/                            # 布局/字符串/主题/图标
├── build.gradle  settings.gradle  gradle.properties
└── local.properties                       # sdk.dir（不入库）
```

## 编译

### 方式一：Android Studio（推荐）

1. Android Studio → `Open` → 选择 `YinziCrawler` 目录
2. 等待 Gradle Sync 完成（首次会下载依赖）
3. `Run ▶` 或 `Build → Build APK(s)`

### 方式二：命令行

```bash
export ANDROID_HOME=/path/to/Android/Sdk
cd YinziCrawler
./gradlew assembleDebug          # 或 gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

### 1. 获取 Cookie（重要）

鱼吧很多内容需要登录态，不填 Cookie 会抓不到或抓不全：

1. 用电脑浏览器打开 https://yuba.douyu.com ，登录斗鱼账号
2. 进入 https://yuba.douyu.com/discussion/561/posts （寅子鱼吧）
3. 按 `F12` → `Network` 标签 → 刷新页面
4. 点第一条请求 → `Headers` → 找到 `Cookie:` 那一行 → 复制整行值
5. App 里点右上角「设置」→ 粘贴 Cookie → 保存

> Cookie 只保存在本机，不会上传。Cookie 一般几天到几周会过期，过期了重新复制一次。

### 2. 改 group_id

默认是寅子 `561`。想爬别的鱼吧，把浏览器鱼吧地址里的数字填进设置即可，例如：
- 寅子：`561`
- 官方：`74`

### 3. 下载

- 点帖子卡片右下角「保存」→ 下载该帖全部图片/视频
- 点底部「下载本页」→ 下载当前列表里所有帖子的全部媒体
- 点单个图片 → 预览；点单个视频缩略图 → 直接下载该视频

### 4. 查看下载结果

打开手机相册，在 `Pictures/YinziCrawler/Images` 和 `Movies/YinziCrawler/Videos` 里。

## 接口与容错说明

斗鱼的鱼吧接口是未公开的，且经常变动。本项目采用三级容错：

1. **wb-api JSON**：`https://yuba.douyu.com/wb-api/group/{group_id}/post`
2. **HTML 页面 + `__NEXT_DATA__`**：抓 `discussion/{id}/posts` 页面，解析内嵌的 Next.js JSON
3. **DOM 兜底**：直接抓 `<img>` / `<video>` 标签

解析器（`YubaParser.kt`）用「递归遍历 JSON 树 + key 语义匹配」提取图片/视频 URL，不硬编码字段名，斗鱼改字段也能撑一阵。如果某天彻底失效，按 README 的「获取 Cookie」重新填一次，并把 `YubaApi.kt` 里的路径对照浏览器实际请求改一下即可。

## 已知限制

- 鱼吧里的视频如果是 m3u8 切片流（非 mp4 直链），无法直接下载，需要额外集成 m3u8 合并，暂未做
- 部分付费/VIP 回放视频需要 VIP 账号的 Cookie 才能拿到直链
- 大量下载会触发风控，建议控制频率
