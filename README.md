Incoming call tutorial
====================

This tutorial will focus on how the app will be notified when a call is being received and how to either accept it or terminate it.

We'll also cover how to toggle the microphone and the speakerphone during an active call.

If you want to test it on either a device or an emulator, you'll need another SIP client to make the call. If you don't, you can use the [outgoing call tutorial](https://gitlab.linphone.org/BC/public/tutorials/-/tree/master/android/kotlin/4-OutgoingCall) to do it.

Note that once again changes to `app/build.gradle` and `AndroidManifest.xml` files were made to enable some features in our SDK.