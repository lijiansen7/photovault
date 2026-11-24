# 混淆 / 裁剪规则

# 数据模型字段由手写 org.json 解析，不依赖反射；保留是为了以后加 Gson/Moshi 不出事
-keep class com.photovault.app.data.** { *; }

# WorkManager 用反射实例化 Worker：类名被混淆会导致「任务找不到」而静默失败
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class com.photovault.app.work.** { *; }

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn androidx.media3.**

# Kotlin / Compose 需要的元数据
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,Exceptions
