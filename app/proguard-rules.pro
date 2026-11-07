# 루나 프렌드 드라이브 — 출시 빌드 코드 축소 규칙

# Pleos Connect SDK: 차량 서비스와 IPC(AIDL·Parcelable)로 통신하므로 이름을 바꾸거나 지우면 안 됨
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# AAOS Car API: 차량 기기에서만 있는 시스템 라이브러리 (useLibrary("android.car"))
-dontwarn android.car.**

# 리스너·Parcelable 등 리플렉션에 쓰이는 정보 유지
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 크래시 로그에서 줄 번호를 볼 수 있게
-keepattributes SourceFile,LineNumberTable
