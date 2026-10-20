#!/usr/bin/env python3
"""Patch the generated Kotlin bindings for two UniFFI quirks.

`CoreError` variants carry a field named `message`, which collides with
`Throwable.message` in Kotlin. Rename the field to `reason` in the exception
classes and their converter (the wire format is positional, so nothing else
changes). Swift keeps `message`.

`TranscriptView.close` collides with `AutoCloseable.close`; rename the Kotlin
spelling to `closeTranscript` (the native symbol is unchanged).

    scripts/android/fix-bindings.py <zeron_core.kt>
"""
import re
import sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()

start = src.index("sealed class CoreException")
# The converter object for the error follows the class; patch up to its end.
conv = src.index("object FfiConverterTypeCoreError", start)
end = src.index("\n}\n", conv) + 3
region = src[start:end]

region = region.replace("val `message`: kotlin.String", "val `reason`: kotlin.String")
region = region.replace('get() = "message=${ `message` }"', 'get() = "message=${ `reason` }"')
region = region.replace("value.`message`", "value.`reason`")

if "val `message`: kotlin.String" in region:
    sys.exit("fix-bindings: CoreException still has a `message` field")
out = src[:start] + region + src[end:]
out = out.replace("fun `close`()", "fun `closeTranscript`()")
open(path, "w", encoding="utf-8").write(out)
print("patched", path)
