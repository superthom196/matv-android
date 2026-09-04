# kotlinx.serialization: keep generated serializers for our models.
-keepclassmembers class io.github.superthom196.hifitv.ma.** { *** Companion; }
-keepclasseswithmembers class io.github.superthom196.hifitv.ma.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.superthom196.hifitv.ma.**$$serializer { *; }
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
