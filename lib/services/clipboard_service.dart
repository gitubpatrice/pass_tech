import 'dart:async';
import 'package:flutter/services.dart';

class ClipboardService {
  static Timer? _timer;
  static int clearAfterSeconds = 30;

  static const _channel =
      MethodChannel('com.passtech.pass_tech/secure_clipboard');

  static Future<void> _copySensitive(String text) async {
    try {
      await _channel.invokeMethod('copySensitive', {'text': text});
    } catch (_) {
      // Fallback for non-Android platforms / channel unavailable
      await Clipboard.setData(ClipboardData(text: text));
    }
  }

  static Future<void> _clearNative() async {
    try {
      await _channel.invokeMethod('clear');
    } catch (_) {
      await Clipboard.setData(const ClipboardData(text: ''));
    }
  }

  static Future<void> copyWithAutoClear(String text, {VoidCallback? onCleared}) async {
    HapticFeedback.lightImpact();
    await _copySensitive(text);
    _timer?.cancel();
    if (clearAfterSeconds > 0) {
      _timer = Timer(Duration(seconds: clearAfterSeconds), () async {
        await _clearNative();
        onCleared?.call();
      });
    }
  }

  /// Cancels the pending auto-clear AND wipes the clipboard immediately.
  /// Used on app pause to avoid leaving secrets in the clipboard if the
  /// process is killed before the timer fires.
  static Future<void> cancelAndClear() async {
    _timer?.cancel();
    _timer = null;
    await _clearNative();
  }

  static void cancel() {
    _timer?.cancel();
    _timer = null;
  }
}
