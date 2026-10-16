# RM 和 RMVB 接入与验收

`1.8.0-0.16.0-rm1-alpha01` 是供应用和设备验收的测试版，保持 Media3 1.8.0、minSdk 21。
源码基线为已发布的 `v1.8.0-0.16.0-vc1.5`，提交
`fe095ec73aa281e23bd172d6dc3704ffcb8fb5a2`；开发分支为 `feature/realmedia-prototype`。

## 原型结论与实际架构

关卡 A 通过后复用现有 NextLib FFmpeg 音视频 Renderer。关卡 B 的直接 Extractor 原型
无法同步满足 FFmpeg 在 `rm_read_header` 中读取尾部索引再回到媒体数据的要求：
`ExtractorInput` 不能在这个原生调用尚未返回时要求上层重开输入。
返回 EAGAIN 的诊断原型能出现缺索引的“成功打开”，在长样本上还会破坏后续解封装。
测试保留这一失败证据，不把它当作可继续的挂起机制。

因此采用需求允许的唯一后备入口，正式实现没有同时维护第二套 Extractor 路径：

```text
现有 ExoPlayer
  → NextMediaSourceFactory，使用应用 DataSource 检查 .RMF 文件头
  → RealMediaSource / RealMediaPeriod
  → Media3 Loader → 应用 DataSource → 自定义 AVIO → FFmpeg libavformat
  → Media3 SampleQueue 和 LoadControl
  → NextLib 现有音视频 Renderer → FFmpeg 解码 → 原有 Surface / AudioSink
```

所有文件及 HTTP/HTTPS 操作均由调用方 DataSource 完成。FFmpeg 打开解封装器时不传 URL，
不建立另一个网络连接。HLS/DASH/SS 直接交还原工厂；其他渐进式内容通过四字节识别后，
非 `.RMF` 内容交还原工厂，因此 MP4/MKV 保留应用原有 Extractor 定制。
渐进式输入会多一次文件头探测，应用 TransferListener 会观察到这次真实读取。

## 应用接入

本地先执行 `./gradlew publishToMavenLocal -PreleaseSigningEnabled=false`，或使用测试发布仓库。
将依赖升级为：

```kotlin
implementation("io.github.178meorg:nextlib-media3ext:1.8.0-0.16.0-rm1-alpha01")
```

在已有工厂外注册，使用同一个应用 DataSource.Factory：

```kotlin
val factory = NextMediaSourceFactory(dataSourceFactory, existingMediaSourceFactory)
val player = ExoPlayer.Builder(context, existingRenderersFactory)
    .setMediaSourceFactory(factory)
    .build()
```

导入 `io.github.anilbeesetti.nextlib.media3ext.realmedia.NextMediaSourceFactory`。
现有 `NextRenderersFactory` 必须启用扩展 Renderer（ON 或 PREFER）；库继续沿用原有默认值。
应用自有字幕/ASS 工厂包在 `NextMediaSourceFactory` 外，继续负责合并外置字幕。
独立使用此工厂的调用方也应保留自己的字幕合并入口。

## 解码与参数

解封装仅传递已验证的原始编码名、extradata、宽高、采样率、声道数及 block_align，
不跨 JNI 传递 FFmpeg 内部结构。RV30 需要宽高；RV40 和 Cook 保留 extradata。
Cook 的声道掩码来自 extradata，已用六声道样本验证，不增加独立的声道布局协议。
首版以 RV30/RV40 和 Cook 为验收基线，已有 AAC 解码路径继续复用。
其他 RealAudio 编码不作兼容承诺，未验证的编码明确报不支持。

现有视频 SimpleDecoder 调度没有发送原生 EOS，RV30/RV40 各丢失一帧延迟帧。
`FfmpegVideoDecoder` 改用有界输入/输出池的收发调度，单包交付后收完输出，并在 EOS
发送空包排空。Surface、像素转换和视频 Renderer 保持复用；MP4/MKV 和已有 Renderer
回归覆盖此次共享改动。Cook 在 FFmpeg 中没有 flush 回调，仅清理通用缓存不足以恢复
重叠样本和随机状态，因此沿用现有 TrueHD 的上下文重建方式。音频重采样代码继续复用。

## 时间轴、拖动和错误处理

所有轨道共用来自 format/stream start_time 的媒体原点；时间基在 JNI 统一换算为微秒。
优先 PTS、其次 DTS；两者都缺失时，先采用该轨道已知的 start_time，随后按包时长推进。
seek 后缺失时间戳使用预滚锚点，并保留晚起轨道的已知起点；保留负时间戳和压缩包解码顺序。
seek 目标反向映射到 FFmpeg 时间轴，重建解封装私有状态，从目标前两秒的前置索引关键帧
开始，交给现有 Renderer 完成参考帧、音频预滚及目标前输出丢弃。
实际目标帧/像素比对与公开入口的 Surface 输出测试共同验证定位，不以 av_seek_frame
返回成功作为画面已准备好的证据。

允许拖动须同时具备已知输入长度、有效的文件级索引、已知时长，并通过中段和尾段的定位探测。
每次索引/seek 读取预算为 8 MiB、30 秒；时间预算在应用 DataSource 的读/开调用返回时检查，
单次阻塞仍由该 DataSource 的连接/读取超时约束。TransferListener 统计缓存命中及服务端
忽略 Range 时实际跳读的字节。无索引或超预算输入降级顺序播放，不能只凭 HTTP Range
宣称可拖动。未知时长输入在顺序播放结束后用样本末尾更新时间轴，仍不声明可拖动。

原始 Java 网络 IOException 跨 JNI 保留类型和对象，不用 AVERROR(EIO) 掩盖认证/超时原因。
Media3 LoadErrorHandlingPolicy 控制重试；已准备且可定位的内容从当前播放位置重开并预滚。
无法有界定位的内容在中途失败时保留错误，不通过从头下载冒充恢复。解析/不支持/超预算
错误不无限重试。取消依靠 Loader 中断、AVIO 中断回调和调用方 DataSource 超时；关闭及
原生资源释放在拥有输入的加载线程完成，避免跨线程关闭引入竞争。

## 样本与可复现验证

公开样本 URL、SHA-256、编码和已有损坏诊断见
[`realmedia_samples.json`](src/test/cpp/realmedia_samples.json)。二进制下载到忽略的
`build/realmedia`，不提交到 Git，不打入发布 AAR。公开 RV30/RV40 样本均有 FFmpeg
诊断警告，参考测试仍要求完整到达 EOS；截断文件和 ATRAC3 文件作为负例保留。

主机原型使用 Python 3.12+、C++17 编译器和 make。

```sh
# 主机参考解封装、原生解码、100 个随机目标帧的像素校验及负例
python3 media3ext/src/test/cpp/run_realmedia_probe.py --include-negative

# 在可丢弃的 Android 设备/模拟器运行 JNI、DataSource 和完整 ExoPlayer 测试
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/sdk \
python3 media3ext/src/test/cpp/run_realmedia_probe.py \
  --android --serial device-id --include-negative \
  --local-sample /path/to/private.rmvb --long-playback

./gradlew assembleRelease :media3ext:testDebugUnitTest :media3ext:lintRelease \
  publishToMavenLocal -PreleaseSigningEnabled=false
python3 ffmpeg/test_setup.py
```

Android 原型比对生产 JNI 解码函数与参考输出，检查完整帧/PCM、时间戳、三次 flush、EOS，
并构造一包两个 Cook 块。AVIO 测试覆盖缓冲回退、外部重开、取消、原始输入错误、100 次
定位和重复释放。公开工厂测试实际使用 SampleQueue、AudioTrack 和 Surface，覆盖本地、
HTTP 缓存/认证、HTTPS、暂停、倍速、续播、100 次拖动、错误重试、EOS、MP4/MKV 回归
以及 HLS 原工厂委派。可选长样本测试以 8 倍速完整播放，周期比对视频时间与音频媒体时钟；
该测试有助于发现累计漂移，不能替代 1 倍速实机听看验收。

生成结果为 `build/realmedia/host-results.json`、`user-sample-results.json`、
`android-test-summary.json` 和 `android-probe.log`。交付目录附带这些记录及源码哈希；
具体通过项、设备指纹和计数以结果文件为准。Android 测试专用 TLS 身份和私有样本仅进入
选择启用的测试 APK，不进入正式 AAR。

## 发布与剩余验收

2026-10-16 的最终检查通过：23 项 JVM 单元测试、Release lint、FFmpeg setup 检查和
29 项使用公开样本的 Android 测试（原型 10、公开播放入口 9、视频 Renderer 9、DecoderManager 生命周期 1）。
私有样本及其结果仅保存在本地忽略目录，不纳入开源记录。
源码哈希、设备指纹及逐项结果见
[`Android 结果`](src/test/cpp/results/realmedia-android-2026-10-16.json)，
样本输出及定位成本见
[`参考结果`](src/test/cpp/results/realmedia-reference-2026-10-16.json)。

Release AAR 构建四个 ABI：armeabi-v7a、arm64-v8a、x86、x86_64。当前可用的运行环境为
Android 15 / API 35 的 x86_64 模拟器；API 21 和 ARM 实机播放、长时间 1 倍速听看、
应用交互及端到端业务播放仍须由调用方补验。

alpha/beta/rc 发布在 GitHub 标为 prerelease，避免作为稳定版
验收结论。远程发布状态以实际 Release/仓库记录为准，本地 Maven 安装不等于远程发布。
