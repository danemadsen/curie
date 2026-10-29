# ONNX Runtime's JNI code finds its Java classes, fields and methods by name; its AAR ships no consumer
# rules for them, so R8 must leave the whole API untouched.
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class ai.onnxruntime.** {
    native <methods>;
}
