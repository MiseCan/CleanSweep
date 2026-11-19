# CleanSweep 相册清理

Android 相册工具，应用只提供两个入口。

- **滑动整理**：左滑加入待删除，右滑保留。支持撤销上一步。点按回收站按钮后，由 Android 确认移除。
- **相似照片**：优先读取媒体库拍摄时间，缺失时读取 EXIF。从组内第一张起算 120 秒，在组内用 DCT pHash、颜色直方图和 ORB + RANSAC 比对。结果可逐张查看和勾选。

Android 11 及以上使用系统回收站。Android 10 使用系统的逐张删除授权。
应用只申请照片访问权限，Android 14 及以上支持部分照片授权。

## Build

JDK 21 and Android SDK 37. Run `gradlew :app:assembleDebug`.
Debug and release builds support only Android devices and emulators with the arm64-v8a ABI.
The interface uses official Compose Material 3 Expressive 1.5.0-rc01 and
[Google Material Symbols Rounded](https://github.com/google/material-design-icons/tree/master/symbols/web).
[Miuix Navigation](https://github.com/compose-miuix-ui/miuix/tree/v0.9.4/miuix-nav)
handles the full-screen photo preview transition.

### Optional model weights

The source checkout excludes ONNX weights and local APKs. To enable model scoring
in a local build, provide a compatible image encoder at one of these paths:

- `app/src/main/assets/models/mobilenet-v3-small.onnx` for MobileNetV3-Small 1.0×
  (224 × 224 RGB input and a 576-element embedding).
- `app/src/main/assets/models/mobileclip-s0.onnx` for MobileCLIP-S0
  (256 × 256 RGB input and a 512-element embedding).

Both models use a float32 NCHW tensor named `image` and return an embedding of
shape `[1, N]`. The app can build without weights and falls back to its
traditional similarity algorithms when the selected encoder is unavailable.
Use weights under their respective licenses. Apple's MobileCLIP-S0 weights
are licensed for research only. See the
[Apple model notice](app/src/main/assets/models/MobileCLIP-S0-LICENSE.txt).


CleanSweep is based on [LoopOtto/CleanSweep](https://github.com/loopotto/CleanSweep).
See `LICENSE` and `CONTRIBUTING.md` for project terms. Google Material Symbols
are licensed under [Apache 2.0](https://github.com/google/material-design-icons/blob/master/LICENSE).
