# RM/RMVB 实验版

基于 Media3 1.8.0、FFmpeg 6.0，保留 Android API 21 最低版本。

## 接入

用本 fork 编译的 `media3ext-release.aar` 替换旧版 AAR，保留 Media3 1.8.0 依赖：

```kotlin
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffextractor.NextExtractorsFactory

val renderers = NextRenderersFactory(context)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
val extractors = NextExtractorsFactory(existingExtractorsFactory)
val sources = DefaultMediaSourceFactory(existingDataSourceFactory, extractors)
val player = ExoPlayer.Builder(context, renderers)
    .setMediaSourceFactory(sources)
    .build()
```

`NextExtractorsFactory()` 默认包含 Media3 的全部标准 Extractor。也可以直接把
`FfmpegExtractor()` 添加到现有工厂的数组中，再统一套调试／统计包装器。
每次创建数组必须新建 Extractor，不要跨播放器共享。

FFmpeg 扩展 renderer 必须启用；`ON` 即可，不需要把所有视频强制软解。

## 第一版范围

- 优先用 4 字节 `.RMF` 文件头识别 RM，不依赖 URL 后缀；不匹配时再交给标准 Extractor，
  避免先执行其他格式的长距离探测，不接管 MP4、MKV、TS 等现有格式。
- RealVideo：RV10、RV20、RV30、RV40。
- RealAudio：Cook、SIPR、14.4、28.8、ATRAC3；容器内 AAC、AC3、MP3 沿用已有解码器。
- 传递 extradata、视频尺寸、codec tag、音频 block alignment 和采样参数。
  私有参数只用于 Nextlib 专用 MIME；AAC 等标准格式仅携带合法的 codec-specific data，
  可由 ExoPlayer 的系统 MediaCodec renderer 正常接管。
- 拒绝越界索引，避免截断文件触发超出文件长度的 HTTP Range 请求。
- 标准 `.rm`／`.rmvb` 渐进式文件播放；不包含 DRM、RTSP、独立旧版 `.ra` 文件支持。
- 已启用的解码器不等于每一种历史文件都已验证；当前样片覆盖 RV10+AC3、RV30+Cook、RV40+Cook。

## 数据源和 seek

FFmpeg 使用自定义 AVIO，通过原有 `ExtractorInput` 读取数据，所有实际 I/O 均由
Media3 加载线程完成。鉴权、缓存、超时、重试和传输统计继续由应用的数据源控制。
不会把 URL 交给 FFmpeg 另开连接。

每个 Extractor 有一个解封装工作线程。线程之间最多挂起一个读取／输出请求，
普通播放保持 FFmpeg 上下文；seek 调用 `av_seek_frame`，通过 `RESULT_SEEK`
让 Media3 重开所需字节位置。新 seek 会丢弃前一次操作尚未输出的样本。
停止加载时保留挂起请求供恢复或重试，release 唤醒工作线程并释放原生资源。

第一版只在长度已知且时长有效时公布可 seek。远端随机读取的效率依赖服务器
Range 支持；无索引或索引损坏的文件还依赖 FFmpeg 的回退搜索能力。

所有解封装器仍编译进 `libavformat`，因为同仓库 `mediainfo` 共用 FFmpeg 产物。
播放入口只开放 RM；没有为这次实验裁掉 `mediainfo` 的其他格式支持。

## 构建与测试

```bash
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :media3ext:assembleRelease
```

产物：`media3ext/build/outputs/aar/media3ext-release.aar`，包含四种 ABI。
FFmpeg 配置或 ABI 列表变化会使构建缓存失效，失败的构建不会被当成成功缓存。

Linux 主机集成测试（不启动 Android 模拟器）：

```bash
bash media3ext/src/test/native/prepare-samples.sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :media3ext:testDebugUnitTest \
  -x ffmpegSetup -PnextlibHostTests
```

需要 JDK 17、C++ 编译器、make、curl、tar、ffmpeg，以及首次下载测试依赖所需网络。
测试独立编译同版本 FFmpeg 6.0，加载实际 Extractor、JNI 解封装和音视频解码代码。
Android Surface 函数在主机测试中使用会立即失败的桩，不模拟屏幕输出。
因此这些测试不代表真机画面渲染、音频输出、长时间音画同步和 Android 5 运行验证。

样片默认放在 `/tmp/nextlib-samples`，可用 `-PnextlibSamples=/path` 指定。
测试包含文件头识别、音视频轨道、实际 JNI 解码及 seek 后解码、前后 seek、EOF 后重播、
连续 seek、读取失败重试、未知长度输入、释放和坏头错误，共 10 项（含 AAC 初始化参数回归）。
部分公开样片本身是截断片段，FFmpeg 的索引／缺失参考帧警告不作为正常完整影片的质量结论。

样片不纳入 Git。公开样片来自 FFmpeg samples 的 `real/VC-RV30` 和 `real/VC-RV40`。
