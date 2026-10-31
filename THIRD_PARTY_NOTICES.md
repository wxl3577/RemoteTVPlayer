# Third-party components

This application dynamically links the unmodified VideoLAN LibVLC Android library 3.7.7, published as org.videolan.android:libvlc-all:3.7.7 on Maven Central.

LibVLC is distributed under the GNU Lesser General Public License, version 2.1 or later. Copyright belongs to VideoLAN and the contributors identified in the library source. Native dependencies carry their respective licenses.

- Library artifacts and Java source archive: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/
- Android library source and build instructions: https://code.videolan.org/videolan/vlc-android
- VLC native source: https://code.videolan.org/videolan/vlc
- Native library build documentation: https://wiki.videolan.org/AndroidCompile/

The complete application source and Gradle project are published in this repository. The native .so libraries are packaged without modification; they can be replaced by a compatible rebuilt library when rebuilding and signing this application. This application does not prevent reverse engineering for debugging modifications of the LGPL library.

A copy of the LGPL license is bundled in app/src/main/assets/LGPL-2.1.txt.
