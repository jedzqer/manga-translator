# Manga Translator

一个计划中的安卓漫画翻译 App，通过 LLM 识别漫画内容并翻译。

## 目标
- 读取漫画图片或页面，日文优化
- 识别文本区域并提取内容
- 调用 LLM 完成翻译
- 在原图上进行可视化覆盖

## 现状
- 已搭建基础 Android 框架与构建流程

## 本地构建与部署
1) 准备环境：JDK 17、Android SDK (platform 34 + build-tools 35.0.0)
2) `https://github.com/jedzqer/manga-translator.git`
3) 放置模型文件（不纳入 Git）：
   - `assets/comic-speech-bubble-detector.onnx` 模型地址：https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m
   - `assets/encoder_model.onnx`、`assets/decoder_model.onnx` 模型地址：https://huggingface.co/l0wgear/manga-ocr-2025-onnx
