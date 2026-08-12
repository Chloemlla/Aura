# Native Compliance Inventory

Generated from local Gradle cache artifacts. This is a factual payload inventory for release review; it does not replace legal review.

- Gradle cache: `~/.gradle/caches/modules-2/files-2.1`

## Coordinate Summary

| Coordinate | Artifact | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `io.github.junkfood02.youtubedl-android:common:0.18.1` | `common-0.18.1.aar` | 5186 | `e0c0530bdbdf203e1598e900085d3582e27360731872872f3a67f23a9b24c0b5` |
| `io.github.junkfood02.youtubedl-android:common:0.18.1` | `common-0.18.1.pom` | 2365 | `de96f5a108c493e464d0e3fe7a73ae21a10d71f98f49024e043650bf22381b78` |
| `io.github.junkfood02.youtubedl-android:common:0.18.1` | `common-0.18.1.module` | 3724 | `6e1cd6c75a8f416cbcf1ee36443fc4a7df542a09a56ab51d2220d0a639aa28fe` |
| `io.github.junkfood02.youtubedl-android:library:0.18.1` | `library-0.18.1.aar` | 59213110 | `579b5fb480892b1abc2b218c2089699d52759cc8d7ba256bf876453f0365faef` |
| `io.github.junkfood02.youtubedl-android:library:0.18.1` | `library-0.18.1.pom` | 2773 | `98f5eb540b7ec22ddd73373a7e87149d0c490e0790cfeeb0343285a643d64690` |
| `io.github.junkfood02.youtubedl-android:library:0.18.1` | `library-0.18.1.module` | 4111 | `3e8ad9aa05cd197bc7ad038c235055b11b2dad74cbb1b4ecc99fbc82a0ff0c9f` |
| `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` | `ffmpeg-0.18.1.module` | 3746 | `89d9c9577d0a922396cb1c6dad51d004ed8f67b8981f4b5cdf457ec4c8b8a6f7` |
| `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` | `ffmpeg-0.18.1.aar` | 139371444 | `0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49` |
| `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` | `ffmpeg-0.18.1.pom` | 2377 | `5f4f089a20eb3240cfccfb0082235e1bd44c8109f26cdd8da10247709bfa5071` |
| `com.github.teamnewpipe:NewPipeExtractor:v0.26.3` | `NewPipeExtractor-v0.26.3.module` | 4164 | `efeccee23d3bc1bb044b1a0c0338a19830bb569c9db07f541147df39ee9a4d4c` |
| `com.github.teamnewpipe:NewPipeExtractor:v0.26.3` | `NewPipeExtractor-v0.26.3.pom` | 2438 | `933016a107bf22c8df7a437a4bc493acc75d82b9f71c514351f959a04a01bf5d` |
| `com.github.teamnewpipe:NewPipeExtractor:v0.26.3` | `NewPipeExtractor-v0.26.3.jar` | 801535 | `8c53b4b4fb25c8a2f5789ec00f0532fb337b91d6f0f384fd02efd1dc52e7fba9` |

## Upstream Source And License References

| Coordinate | License | Source | License text | Review note |
| --- | --- | --- | --- | --- |
| `io.github.junkfood02.youtubedl-android:common:0.18.1` | GPL-3.0 | https://github.com/yausername/youtubedl-android/tree/master/common | https://raw.githubusercontent.com/yausername/youtubedl-android/master/LICENSE | Wrapper component; no native payload entries matched this inventory. |
| `io.github.junkfood02.youtubedl-android:library:0.18.1` | GPL-3.0 | https://github.com/yausername/youtubedl-android/tree/master/library | https://raw.githubusercontent.com/yausername/youtubedl-android/master/LICENSE | Bundles yt-dlp plus Python/native runtime payloads; exact nested payload source evidence is required for release review. |
| `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` | GPL-3.0-or-later for the resolved payload; FFmpeg base licensing depends on build flags | https://github.com/yausername/youtubedl-android/tree/master/ffmpeg | https://www.ffmpeg.org/legal.html | Embedded libraries expose FFmpeg 7.1.1 configure evidence; exact Termux source, patch, and dependency correspondence still needs release-owner review. |
| `com.github.teamnewpipe:NewPipeExtractor:v0.26.3` | GPL-3.0-or-later | https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.3 | https://raw.githubusercontent.com/TeamNewPipe/NewPipeExtractor/v0.26.3/LICENSE | Single NewPipeExtractor artifact used by Aura's YouTube metadata path. |

## Payload Source And Build References

| Payload | License / status | Evidence | Review note |
| --- | --- | --- | --- |
| yt-dlp payload | Unlicense for source; zipimport release file also includes ISC/MIT components | https://github.com/yt-dlp/yt-dlp/tree/2025.11.12 | The report extracts the bundled version, origin, and git head from res/raw/ytdlp. |
| Python runtime payload | PSF-2.0 plus packaged dependency licenses | https://docs.python.org/3.12/license.html | The report extracts python3.12 directories from libpython.zip.so; exact Termux package source set still needs release-owner review. |
| QuickJS runtime payload | MIT | https://bellard.org/quickjs/ | libqjs.so is present in youtubedl-android library AARs; exact packaged QuickJS revision is not encoded in the AAR. |
| FFmpeg payload | GPL-3.0-or-later for the resolved payload; FFmpeg base licensing depends on build flags | https://www.ffmpeg.org/legal.html | Embedded configure lines include GPL/version3 flags; FFmpeg legal guidance still requires matching source and build evidence for the shipped binaries. |
| Aura FFmpeg source correspondence checklist | Release review evidence | docs/legal/ffmpeg-source-correspondence.md | Records resolved AAR hash, embedded FFmpeg 7.1.1 configure evidence, source candidates, and remaining owner actions. |
| youtubedl-android FFmpeg build notes | Build evidence, not a separate license | https://raw.githubusercontent.com/yausername/youtubedl-android/master/BUILD_FFMPEG.md | Upstream describes a Termux package build path; Aura still needs exact source/build correspondence for the resolved 0.18.1 AAR. |
| youtubedl-android Python build notes | Build evidence, not a separate license | https://raw.githubusercontent.com/yausername/youtubedl-android/master/BUILD_PYTHON.md | Upstream describes a Termux package build path; the resolved AAR contains python3.12 despite older README/build-note examples. |

## Payload Entries

### `io.github.junkfood02.youtubedl-android:common:0.18.1` - `common-0.18.1.aar`

No native/payload/license entries matched the inventory filters.

### `io.github.junkfood02.youtubedl-android:library:0.18.1` - `library-0.18.1.aar`

| Entry | Bytes | Nested entries | Facts |
| --- | ---: | ---: | --- |
| `res/raw/` | 0 |  |  |
| `res/raw/ytdlp` | 3170726 | 1122 | yt-dlp version: 2025.11.12; yt-dlp git head: 335653be82d5ef999cfc2879d005397402eebec1; yt-dlp origin: yt-dlp/yt-dlp; nested sample: yt_dlp/version.py, yt_dlp/postprocessor/ffmpeg.py, yt_dlp_ejs/_version.py |
| `jni/` | 0 |  |  |
| `jni/arm64-v8a/` | 0 |  |  |
| `jni/arm64-v8a/libpython.so` | 4384 |  |  |
| `jni/arm64-v8a/libpython.zip.so` | 14305904 | 1007 | python payload: python3.12; nested sample: usr/lib/, usr/lib/libform.so, usr/lib/libz.so, usr/lib/libffi.so, usr/lib/libtermcap.so, usr/lib/libcurses.so.6.5, usr/lib/libexpat.so.1.11.1, usr/lib/libgdbm_compat.so, ... |
| `jni/arm64-v8a/libqjs.so` | 916104 |  |  |
| `jni/armeabi-v7a/` | 0 |  |  |
| `jni/armeabi-v7a/libpython.so` | 3016 |  |  |
| `jni/armeabi-v7a/libpython.zip.so` | 12822487 | 1007 | python payload: python3.12; nested sample: usr/lib/, usr/lib/libform.so, usr/lib/libz.so, usr/lib/libffi.so, usr/lib/libtermcap.so, usr/lib/libcurses.so.6.5, usr/lib/libexpat.so.1.11.1, usr/lib/libgdbm_compat.so, ... |
| `jni/armeabi-v7a/libqjs.so` | 634624 |  |  |
| `jni/x86/` | 0 |  |  |
| `jni/x86/libpython.so` | 3240 |  |  |
| `jni/x86/libpython.zip.so` | 13979299 | 1009 | python payload: python3.12; nested sample: usr/lib/, usr/lib/libform.so, usr/lib/libz.so, usr/lib/libffi.so, usr/lib/libtermcap.so, usr/lib/libcurses.so.6.5, usr/lib/libexpat.so.1.11.1, usr/lib/libgdbm_compat.so, ... |
| `jni/x86/libqjs.so` | 949596 |  |  |
| `jni/x86_64/` | 0 |  |  |
| `jni/x86_64/libpython.so` | 4272 |  |  |
| `jni/x86_64/libpython.zip.so` | 14302766 | 1009 | python payload: python3.12; nested sample: usr/lib/, usr/lib/libform.so, usr/lib/libz.so, usr/lib/libffi.so, usr/lib/libtermcap.so, usr/lib/libcurses.so.6.5, usr/lib/libexpat.so.1.11.1, usr/lib/libgdbm_compat.so, ... |
| `jni/x86_64/libqjs.so` | 981912 |  |  |

### `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` - `ffmpeg-0.18.1.aar`

| Entry | Bytes | Nested entries | Facts |
| --- | ---: | ---: | --- |
| `jni/` | 0 |  |  |
| `jni/arm64-v8a/` | 0 |  |  |
| `jni/arm64-v8a/libffmpeg.so` | 317120 |  |  |
| `jni/arm64-v8a/libffmpeg.zip.so` | 35624931 | 184 | FFmpeg version: FFmpeg version 7.1.1; FFmpeg configure: --arch=aarch64 --as=aarch64-linux-android-clang --cc=aarch64-linux-android-clang --cxx=aarch64-linux-android-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --pkg-config=/home/builder/.termux-build/_cache/android-r28c-api-24-v1/bin/pkg-config --strip=llvm-strip --cross-prefix=aarch64-linux-android- --disable-indevs --disable-outdevs --enable-indev=lavfi --disable-static --disable-symver --enable-cross-compile --enable-gnutls --enable-gpl --enable-version3 --enable-jni --enable-lcms2 --enable-libaom --enable-libass --enable-libbluray --enable-libdav1d --enable-libfontconfig --enable-libfreetype --enable-libfribidi --enable-libgme --enable-libharfbuzz --enable-libmp3lame --enable-libopencore-amrnb --enable-libopencore-amrwb --enable-libopenmpt --enable-libopus --enable-librav1e --enable-librubberband --enable-libsoxr --enable-libsrt --enable-libssh --enable-libsvtav1 --enable-libtheora --enable-libv4l2 --enable-libvidstab --enable-libvmaf --enable-libvo-amrwbenc --enable-libvorbis --enable-libvpx --enable-libwebp --enable-libx264 --enable-libx265 --enable-libxml2 --enable-libxvid --enable-libzimg --enable-libzmq --enable-mediacodec --enable-opencl --enable-shared --prefix=/data/data/com.termux/files/usr --target-os=android --extra-libs=-landroid-glob --disable-vulkan --enable-neon --disable-libfdk-aac; FFmpeg configure sha256: 203d15374b60141718c798b584c751eccc92e18fa7df4f3564f34ca5ff35c1f0; FFmpeg license mode: GPL-3.0-or-later flags present (--enable-gpl --enable-version3); --enable-nonfree not found; nested sample: usr/lib/, usr/lib/libswscale.so, usr/lib/libavformat.so, usr/lib/libass.so, usr/lib/libz.so, usr/lib/libffi.so, usr/lib/libtheoradec.so, usr/lib/librav1e.so.0, ... |
| `jni/arm64-v8a/libffprobe.so` | 233664 |  |  |
| `jni/armeabi-v7a/` | 0 |  |  |
| `jni/armeabi-v7a/libffmpeg.so` | 235228 |  |  |
| `jni/armeabi-v7a/libffmpeg.zip.so` | 30476956 | 184 | FFmpeg version: FFmpeg version 7.1.1; FFmpeg configure: --arch=armeabi-v7a --as=arm-linux-androideabi-clang --cc=arm-linux-androideabi-clang --cxx=arm-linux-androideabi-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --pkg-config=/home/builder/.termux-build/_cache/android-r28c-api-24-v1/bin/pkg-config --strip=llvm-strip --cross-prefix=arm-linux-androideabi- --disable-indevs --disable-outdevs --enable-indev=lavfi --disable-static --disable-symver --enable-cross-compile --enable-gnutls --enable-gpl --enable-version3 --enable-jni --enable-lcms2 --enable-libaom --enable-libass --enable-libbluray --enable-libdav1d --enable-libfontconfig --enable-libfreetype --enable-libfribidi --enable-libgme --enable-libharfbuzz --enable-libmp3lame --enable-libopencore-amrnb --enable-libopencore-amrwb --enable-libopenmpt --enable-libopus --enable-librav1e --enable-librubberband --enable-libsoxr --enable-libsrt --enable-libssh --enable-libsvtav1 --enable-libtheora --enable-libv4l2 --enable-libvidstab --enable-libvmaf --enable-libvo-amrwbenc --enable-libvorbis --enable-libvpx --enable-libwebp --enable-libx264 --enable-libx265 --enable-libxml2 --enable-libxvid --enable-libzimg --enable-libzmq --enable-mediacodec --enable-opencl --enable-shared --prefix=/data/data/com.termux/files/usr --target-os=android --extra-libs=-landroid-glob --disable-vulkan --enable-neon --disable-libfdk-aac; FFmpeg configure sha256: 11215245be9ec6e63a9422d2f2a26f8cbfa361c13f5ca079b3cc780565f2bf38; FFmpeg license mode: GPL-3.0-or-later flags present (--enable-gpl --enable-version3); --enable-nonfree not found; nested sample: usr/lib/, usr/lib/libswscale.so, usr/lib/libavformat.so, usr/lib/libass.so, usr/lib/libz.so, usr/lib/libffi.so, usr/lib/libtheoradec.so, usr/lib/librav1e.so.0, ... |
| `jni/armeabi-v7a/libffprobe.so` | 166580 |  |  |
| `jni/x86/` | 0 |  |  |
| `jni/x86/libffmpeg.so` | 320692 |  |  |
| `jni/x86/libffmpeg.zip.so` | 34544829 | 184 | FFmpeg version: FFmpeg version 7.1.1; FFmpeg configure: --arch=x86 --as=i686-linux-android-clang --cc=i686-linux-android-clang --cxx=i686-linux-android-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --pkg-config=/home/builder/.termux-build/_cache/android-r28c-api-24-v1/bin/pkg-config --strip=llvm-strip --cross-prefix=i686-linux-android- --disable-indevs --disable-outdevs --enable-indev=lavfi --disable-static --disable-symver --enable-cross-compile --enable-gnutls --enable-gpl --enable-version3 --enable-jni --enable-lcms2 --enable-libaom --enable-libass --enable-libbluray --enable-libdav1d --enable-libfontconfig --enable-libfreetype --enable-libfribidi --enable-libgme --enable-libharfbuzz --enable-libmp3lame --enable-libopencore-amrnb --enable-libopencore-amrwb --enable-libopenmpt --enable-libopus --enable-librav1e --enable-librubberband --enable-libsoxr --enable-libsrt --enable-libssh --enable-libsvtav1 --enable-libtheora --enable-libv4l2 --enable-libvidstab --enable-libvmaf --enable-libvo-amrwbenc --enable-libvorbis --enable-libvpx --enable-libwebp --enable-libx264 --enable-libx265 --enable-libxml2 --enable-libxvid --enable-libzimg --enable-libzmq --enable-mediacodec --enable-opencl --enable-shared --prefix=/data/data/com.termux/files/usr --target-os=android --extra-libs=-landroid-glob --disable-vulkan --disable-asm --disable-libfdk-aac; FFmpeg configure sha256: 444e99bffd340bb709024bcfeebe1e38d99a8a5b63de7c5f0583258c48a2fa49; FFmpeg license mode: GPL-3.0-or-later flags present (--enable-gpl --enable-version3); --enable-nonfree not found; nested sample: usr/lib/, usr/lib/libswscale.so, usr/lib/libavformat.so, usr/lib/libass.so, usr/lib/libz.so, usr/lib/libffi.so, usr/lib/libtheoradec.so, usr/lib/librav1e.so.0, ... |
| `jni/x86/libffprobe.so` | 209704 |  |  |
| `jni/x86_64/` | 0 |  |  |
| `jni/x86_64/libffmpeg.so` | 334776 |  |  |
| `jni/x86_64/libffmpeg.zip.so` | 38595503 | 184 | FFmpeg version: FFmpeg version 7.1.1; FFmpeg configure: --arch=x86_64 --as=x86_64-linux-android-clang --cc=x86_64-linux-android-clang --cxx=x86_64-linux-android-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --pkg-config=/home/builder/.termux-build/_cache/android-r28c-api-24-v1/bin/pkg-config --strip=llvm-strip --cross-prefix=x86_64-linux-android- --disable-indevs --disable-outdevs --enable-indev=lavfi --disable-static --disable-symver --enable-cross-compile --enable-gnutls --enable-gpl --enable-version3 --enable-jni --enable-lcms2 --enable-libaom --enable-libass --enable-libbluray --enable-libdav1d --enable-libfontconfig --enable-libfreetype --enable-libfribidi --enable-libgme --enable-libharfbuzz --enable-libmp3lame --enable-libopencore-amrnb --enable-libopencore-amrwb --enable-libopenmpt --enable-libopus --enable-librav1e --enable-librubberband --enable-libsoxr --enable-libsrt --enable-libssh --enable-libsvtav1 --enable-libtheora --enable-libv4l2 --enable-libvidstab --enable-libvmaf --enable-libvo-amrwbenc --enable-libvorbis --enable-libvpx --enable-libwebp --enable-libx264 --enable-libx265 --enable-libxml2 --enable-libxvid --enable-libzimg --enable-libzmq --enable-mediacodec --enable-opencl --enable-shared --prefix=/data/data/com.termux/files/usr --target-os=android --extra-libs=-landroid-glob --disable-vulkan --disable-libfdk-aac; FFmpeg configure sha256: 0945d7c6158ac48c3cb36d81c0db0321444c37383a6b6ca68fd5e580db11e772; FFmpeg license mode: GPL-3.0-or-later flags present (--enable-gpl --enable-version3); --enable-nonfree not found; nested sample: usr/lib/, usr/lib/libswscale.so, usr/lib/libavformat.so, usr/lib/libass.so, usr/lib/libz.so, usr/lib/libffi.so, usr/lib/libtheoradec.so, usr/lib/librav1e.so.0, ... |
| `jni/x86_64/libffprobe.so` | 231304 |  |  |

### `com.github.teamnewpipe:NewPipeExtractor:v0.26.3` - `NewPipeExtractor-v0.26.3.jar`

No native/payload/license entries matched the inventory filters.

## Release Review Notes

- Confirm exact upstream source and license text for every artifact listed above before public release expansion.
- FFmpeg payloads expose embedded configure evidence in this report; release owners still need exact Termux source, patch, dependency-source, and build-log correspondence before publishing changed FFmpeg payloads.
- FFmpeg source correspondence checklist: docs/legal/ffmpeg-source-correspondence.md
- youtubedl-android library payloads include yt-dlp and Python assets that need version/source disclosure.
- Generated Google OSS notices cover Maven coordinates but do not inspect these nested native payloads.
