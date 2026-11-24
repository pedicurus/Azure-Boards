# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------------------------
# Azure Boards app rules
# ---------------------------------------------------------------------------------------------

# Keep line numbers so release crash stack traces stay readable, but hide the source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Retrofit reads generic signatures and annotations of the service interface at runtime.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep,allowobfuscation interface com.example.data.api.AzureDevOpsService

# Type-safe Navigation Compose resolves @Serializable route objects by their serializers.
-keep class com.example.ui.navigation.AzureRoute { *; }
-keep class com.example.ui.navigation.AzureRoute$* { *; }
