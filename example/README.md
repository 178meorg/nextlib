# Nextlib SMB Example

独立测试 APK，直接依赖 `:media3ext`，无需发布 AAR。最低 Android 5.0（API 21）。
应用 ID 见 `example/build.gradle.kts`。

## 使用

1. 点击「添加 SMB」，填写服务器 IP 或主机名，可带端口，例如 `192.168.1.10:445`。
2. 可填共享名／初始目录，例如 `movies/电影`。留空时先列出服务器共享。
3. 填写用户名、密码；域可留空。用户名留空表示访客访问。
4. 点击「连接」，进入目录后点击视频播放。返回会保留目录和列表位置。
5. 服务器列表长按可编辑或删除；读取失败可以刷新或返回编辑连接。

服务器地址、共享路径、用户名、域保存在应用私有配置；密码通过 AES-GCM 加密保存，
随机记录密钥由 Android Keystore 的 RSA 密钥保护，兼容 API 21。
重启 App 后自动恢复密码，删除服务器同时删除该连接的密文。密钥失效时要求重新输入，
不降级为明文保存。旧版未保存过密码的连接需要编辑补填一次。
密码不进入媒体 URI、Intent 或日志。

播放页提供暂停、前后跳转 10 秒、拖动 seek、重试及 FFmpeg 优先切换，显示实际音视频解码器。
默认系统解码器优先；RealVideo 使用 FFmpeg。进入后台释放播放器，返回同一播放页恢复位置。

SMB 文件通过 Media3 DataSource 随机读取；首次播放和 seek 使用同一认证上下文，
不下载整个文件，也不转换成本地 HTTP 服务。数据源按 64 KiB 预读，格式识别中的小读取
直接消费缓存，避免每个字节都发起 SMB 请求；seek 重开时清空旧缓存。目录浏览在后台线程执行。

客户端为 jcifs-ng 2.1.10，允许 SMB2/SMB3 协商；本版不支持服务端强制 SMB3 加密，
也未启用 SMB1。认证失败、共享权限和服务端协议设置可能影响共享列表访问，
此时可在连接设置中直接指定有权限访问的共享名。

## 构建和安装

```bash
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :example:assembleDebug :example:lintDebug
adb -s <手机序列号> install -r example/build/outputs/apk/debug/example-debug.apk
```

首次构建会编译 FFmpeg 四种 ABI，后续复用缓存。

## SMB 集成测试

测试使用本机启用强制签名的临时 Samba 服务，验证认证、共享列表、中文及 `#`/`%` 文件名、随机范围读取、
EOF、越界及错误密码后重试。只监听回环地址，不访问用户 NAS。

```bash
python3 example/src/test/smb/prepare.py
docker build -t nextlib-smb-test:local example/src/test/smb
docker run -d --rm --name nextlib-smb-test -p 127.0.0.1:1446:445 \
  --mount type=bind,source=/tmp/nextlib-smb-share,target=/media,readonly nextlib-smb-test:local
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :example:testDebugUnitTest -PnextlibSmbTestPort=1446
docker stop nextlib-smb-test
```

Docker 构建可通过 `--build-arg DEBIAN_MIRROR=mirrors.aliyun.com` 指定包镜像。
测试账号 `nextlib` / `nextlib-test` 仅用于本机容器。不要向外网开放测试端口。

测试文件 `视频 #100%.mp4` 是用于核对字节的合成数据，不是可播放影片。
真实解码测试见 [RealMedia 说明](../docs/realmedia.md)。

## 真机回归测试

`PlayerActivity` 使用标准 `ExoPlayer`、`PlayerView`，通过 `NextRenderersFactory` 和
`NextExtractorsFactory` 注册 Nextlib 扩展。没有另写播放引擎。

先启动上面的临时 Samba 服务，将 FFmpeg 样片放到共享根目录：
`aac.rm`（RealMedia/AAC）和 `rv40.rm`（RealVideo/Cook）。
样片可使用 `media3ext/src/test/native/prepare-samples.sh` 下载的对应文件。

```bash
./gradlew :example:assembleDebug :example:assembleDebugAndroidTest
adb -s <设备> install -r example/build/outputs/apk/debug/example-debug.apk
adb -s <设备> install -r example/build/outputs/apk/androidTest/debug/example-debug-androidTest.apk
adb -s <设备> reverse tcp:1446 tcp:1446
adb -s <设备> shell am instrument -w -e smbPlayback true \
  <applicationId>.test/androidx.test.runner.AndroidJUnitRunner
adb -s <设备> reverse --remove tcp:1446
```

验证系统 AAC 解码、RealVideo 实际输出及 seek；密码测试验证 Keystore 加密往返与篡改拒绝。
`PasswordPersistenceDeviceTest` 可分两次执行，参数分别为 `-e passwordPersistence save`
和 `-e passwordPersistence restore`，中间 force-stop 主应用，验证跨进程恢复。
测试只使用固定测试凭据，不读取用户密码。
