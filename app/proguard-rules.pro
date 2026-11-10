# Models are (de)serialized with kotlinx.serialization; keep them intact.
-keep class io.github.ddastoor.eventtracker.data.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
