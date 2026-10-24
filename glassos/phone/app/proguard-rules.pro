# The web page calls these by name through addJavascriptInterface.
-keepclassmembers class com.gathin.tvremote.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-dontobfuscate
