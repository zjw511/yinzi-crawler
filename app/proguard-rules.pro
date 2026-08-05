# 默认 proguard 规则，本项目不开启混淆
-keep class com.yinzi.crawler.model.** { *; }
-keepclassmembers,allowobfuscation class * {
  @kotlinx.serialization.SerialName <fields>;
}
