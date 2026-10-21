# AltsCodex SDK consumer rules.
# The SDK and AppAuth use no reflection; AppAuth activities are kept through the merged manifest.
# Keep the public SDK surface so stack traces and API names stay readable in minified apps.
-keep public class com.altscodex.sdk.** { public *; }
